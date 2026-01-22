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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.annotation.PostConstruct;

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
     * ✅ 로컬/부하테스트에서 큐 게이트를 끌 수 있는 스위치
     * - 기본값 true (운영 안전)
     * - application-local.yml 에서 false로 끄면 QUEUE_NOT_ALLOWED 안 뜸
     */
    @Value("${ticketing.queue.enabled:true}")
    private boolean queueEnabled;

    /**
     * - 여기서는 트랜잭션을 잡지 않는다.
     * - Redis/대기열/좌석 존재 체크는 트랜잭션 필요 없음
     * - DB hold는 ReservationService 쪽에서 짧게 트랜잭션으로 처리
     * - 데드락/락 획득 실패는 짧게 재시도(새 트랜잭션)해서 5xx를 줄인다.
     */
    public HoldSeatResult holdSeat(Long scheduleId, String seatNo, Long userId, boolean bypassQueue) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String sn = seatNo.trim().toUpperCase();

        // ✅ Queue Gate (로컬/부하테스트에서 끌 수 있게)
        if (queueEnabled && !bypassQueue) {

            // 대기열을 먼저 탄 적 없으면 자동 진입시켜서 스킵을 막는다.
            if (queueStore.getPosition(scheduleId, userId) == -1L) {
                queueStore.enterQueue(scheduleId, userId);
            }

            boolean canEnter = queueStore.canEnter(scheduleId, userId, ALLOWED_QUEUE_RANK);
            if (!canEnter) {
                throw new BusinessException(ErrorCode.QUEUE_NOT_ALLOWED);
            }
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

        final Long sid = scheduleId;
        final Long uid = userId;
        final String sn = seatNo.trim().toUpperCase();

        // 1) DB hold 취소 (멱등)
        final boolean canceled = reservationService.cancelHoldIfExists(uid, sid, sn);

        // 2) 커밋 후 처리 등록
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {

                // 커밋 이후 "지금 락 소유자" 다시 확인
                Long ownerAfter = null;
                try {
                    ownerAfter = seatLockStore.getLockOwner(sid, sn);
                } catch (Exception ignored) {}

                boolean ownerIsMeAfter = ownerAfter != null && ownerAfter.equals(uid);
                boolean shouldPublishRelease = canceled || ownerIsMeAfter;

                // 3) 락 해제는 항상 시도 (멱등)
                try {
                    seatLockStore.releaseSeat(sid, sn, uid);
                } catch (Exception e) {
                    // TODO: 최소 warn 로그는 남기는게 좋음
                }

                // 4) publish (멱등/조건부)
                if (shouldPublishRelease) {
                    try {
                        seatSseHub.publish(
                                sid,
                                new SeatChangedEvent("RELEASED", sid, sn, false, uid, LocalDateTime.now())
                        );
                    } catch (Exception e) {
                        // TODO: 최소 warn 로그는 남기는게 좋음
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

    @PostConstruct
    public void init() {
        System.out.println("ACTIVE ticketing.queue.enabled = " + queueEnabled);
    }
}
