package com.len.ticketing.application.ticket;

import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.queue.QueueStore;
import com.len.ticketing.domain.ticket.SeatLockStore;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.sse.SeatChangedEvent;
import com.len.ticketing.infra.sse.SeatSseHub;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

@RequiredArgsConstructor
@Service
public class TicketService {

    private static final long SEAT_LOCK_TTL_SECONDS = 300L; // 5분
    private static final long ALLOWED_QUEUE_RANK = 100L;

    private final SeatJpaRepository seatRepository;
    private final QueueStore queueStore;
    private final SeatLockStore seatLockStore;
    private final ReservationService reservationService;
    private final SeatSseHub seatSseHub;

    /**
     * - 여기서는 트랜잭션을 잡지 않는다.
     * - Redis/대기열/좌석 존재 체크는 트랜잭션 필요 없음
     * - DB hold는 ReservationService 쪽에서 짧게 트랜잭션으로 처리
     * - 데드락/락 획득 실패는 짧게 재시도(새 트랜잭션)해서 5xx를 줄인다.
     */
    public HoldSeatResult holdSeat(Long scheduleId, String seatNo, Long userId) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String sn = seatNo.trim().toUpperCase();

        // 대기열을 먼저 탄 적 없으면 자동 진입시켜서 스킵을 막는다.
        if (queueStore.getPosition(scheduleId, userId) == -1L) {
            queueStore.enterQueue(scheduleId, userId);
        }

        boolean canEnter = queueStore.canEnter(scheduleId, userId, ALLOWED_QUEUE_RANK);
        if (!canEnter) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ALLOWED);
        }

        if (!seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn)) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

        // ✅ Redis 락 선점
        boolean locked = seatLockStore.lockSeat(scheduleId, sn, userId, SEAT_LOCK_TTL_SECONDS);
        if (!locked) {
            Long owner = seatLockStore.getLockOwner(scheduleId, sn);
            if (owner != null && !owner.equals(userId)) {
                throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
            }
            return new HoldSeatResult(false, "좌석 선점에 실패했습니다.");
        }

        int maxAttempts = 5;
        long backoffMs = 10;

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    // ✅ 여기서 DB 트랜잭션(hold) 커밋 완료되어야 다음으로 감
                    reservationService.hold(userId, scheduleId, sn);

                    // ✅ (중요) SSE는 "커밋 이후"로 보냄 (트랜잭션/네트워크 IO 분리)
                    publishAfterCommit(scheduleId,
                            new SeatChangedEvent("HELD", scheduleId, sn, true, userId, LocalDateTime.now()));

                    return new HoldSeatResult(true, "좌석 선점에 성공했습니다. 결제를 진행해주세요.");

                } catch (CannotAcquireLockException | DeadlockLoserDataAccessException e) {
                    if (attempt == maxAttempts) throw e;

                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    backoffMs = Math.min(backoffMs * 2, 100);
                }
            }

            // 여기로 오진 않음
            return new HoldSeatResult(false, "좌석 선점에 실패했습니다.");

        } catch (RuntimeException e) {
            // ✅ 실패하면 Redis 락은 무조건 해제(유령락 방지)
            seatLockStore.releaseSeat(scheduleId, sn, userId);
            throw e;
        }
    }

    @Transactional
    public void releaseSeat(Long scheduleId, String seatNo, Long userId) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        final String sn = seatNo.trim().toUpperCase();

        // 1) DB hold 취소 (멱등)
        boolean canceled = reservationService.cancelHoldIfExists(userId, scheduleId, sn);

        // 2) 커밋 후 처리 등록
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {

                // 커밋 이후 "지금 락 소유자" 다시 확인
                Long ownerAfter = null;
                try {
                    ownerAfter = seatLockStore.getLockOwner(scheduleId, sn);
                } catch (Exception e) {
                    // 여기서 조회 실패하면 publish 판단을 보수적으로: canceled 기반으로만
                }

                boolean ownerIsMeAfter = ownerAfter != null && ownerAfter.equals(userId);
                boolean shouldPublishRelease = canceled || ownerIsMeAfter;

                // 3) 락 해제는 항상 시도 (멱등)
                try {
                    seatLockStore.releaseSeat(scheduleId, sn, userId);
                } catch (Exception e) {
                    // 예외 무시하지 말고 최소 로그
                    // log.warn("seatLockStore.releaseSeat failed. scheduleId={}, seatNo={}, userId={}", scheduleId, sn, userId, e);
                }

                // 4) publish (멱등/조건부)
                if (shouldPublishRelease) {
                    try {
                        seatSseHub.publish(
                                scheduleId,
                                new SeatChangedEvent("RELEASED", scheduleId, sn, false, userId, LocalDateTime.now())
                        );
                    } catch (Exception e) {
                        // log.warn("seatSseHub.publish failed. scheduleId={}, seatNo={}, userId={}", scheduleId, sn, userId, e);
                    }
                }
            }
        });
    }



    private void publishAfterCommit(Long scheduleId, SeatChangedEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        seatSseHub.publish(scheduleId, event);
                    } catch (Exception ignore) {}
                }
            });
        } else {
            // holdSeat는 트랜잭션이 없지만, 안전하게 fallback
            try {
                seatSseHub.publish(scheduleId, event);
            } catch (Exception ignore) {}
        }
    }

    public record HoldSeatResult(boolean success, String message) {}
}
