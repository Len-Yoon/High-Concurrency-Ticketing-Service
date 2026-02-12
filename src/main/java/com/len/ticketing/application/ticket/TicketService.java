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

    private final SeatJpaRepository seatRepository;
    private final QueueStore queueStore;
    private final SeatLockStore seatLockStore;
    private final ReservationService reservationService;
    private final SeatSseHub seatSseHub;

    /**
     * 로컬/부하테스트에서 큐 게이트를 끌 수 있는 스위치
     * - 기본값 true (운영 안전)
     */
    @Value("${ticketing.queue.enabled:true}")
    private boolean queueEnabled;

    /**
     * holdSeat:
     * - 여기서는 트랜잭션을 잡지 않는다.
     * - DB hold는 ReservationService 쪽에서 짧게 트랜잭션으로 처리
     */
    public HoldSeatResult holdSeat(Long scheduleId, String seatNo, Long userId, boolean bypassQueue, String queueToken) {

        long t0 = System.nanoTime();

        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        long t1 = System.nanoTime();

        // Queue Gate (PASS 토큰 기반)
        if (queueEnabled && !bypassQueue) {
            // 토큰이 없으면: 큐에는 넣어주되, hold는 막는다 (클라이언트가 /queue/enter로 토큰 받아야 함)
            if (queueToken == null || queueToken.isBlank()) {
                if (queueStore.getPosition(scheduleId, userId) == -1L) {
                    queueStore.enterQueue(scheduleId, userId);
                }
                throw new BusinessException(ErrorCode.QUEUE_NOT_ALLOWED);
            }

            // 토큰 검증 실패
            if (!queueStore.validatePass(scheduleId, userId, queueToken)) {
                if (queueStore.getPosition(scheduleId, userId) == -1L) {
                    queueStore.enterQueue(scheduleId, userId);
                }
                throw new BusinessException(ErrorCode.QUEUE_NOT_ALLOWED);
            }
        }
        long t2 = System.nanoTime();

        boolean exists = seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn);
        long t3 = System.nanoTime();
        if (!exists) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

        boolean locked = seatLockStore.lockSeat(scheduleId, sn, userId, SEAT_LOCK_TTL_SECONDS);
        long t4 = System.nanoTime();

        if ((userId % 50) == 0) {
            System.out.printf(
                    "holdSeat timing sn=%s total=%dms normalize=%dms queue=%dms exists=%dms lock=%dms bypass=%s enabled=%s%n",
                    sn,
                    (t4 - t0) / 1_000_000,
                    (t1 - t0) / 1_000_000,
                    (t2 - t1) / 1_000_000,
                    (t3 - t2) / 1_000_000,
                    (t4 - t3) / 1_000_000,
                    bypassQueue,
                    queueEnabled
            );
        }

        if (!locked) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
        }

        int maxAttempts = 5;
        long backoffMs = 10;

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    reservationService.hold(userId, scheduleId, sn);

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

            return new HoldSeatResult(false, "좌석 선점에 실패했습니다.");

        } catch (RuntimeException e) {
            seatLockStore.releaseSeat(scheduleId, sn, userId);
            throw e;
        }
    }

    // seatId 기반 hold (신규)
    public HoldSeatResult holdSeatById(
            Long scheduleId,
            Long seatId,
            Long userId,
            boolean bypassQueue,
            String queueToken
    ) {
        if (scheduleId == null || seatId == null || userId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String seatNo = findSeatNoByScheduleAndSeatId(scheduleId, seatId);
        return holdSeat(scheduleId, seatNo, userId, bypassQueue, queueToken);
    }

    private String findSeatNoByScheduleAndSeatId(Long scheduleId, Long seatId) {
        return seatRepository.findSeatNoByScheduleIdAndSeatId(scheduleId, seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));
    }

    @Transactional
    public void releaseSeat(Long scheduleId, String seatNo, Long userId) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        final Long sid = scheduleId;
        final Long uid = userId;
        final String sn = seatNo.trim().toUpperCase();

        final boolean canceled = reservationService.cancelHoldIfExists(uid, sid, sn);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {

                // pass 회수 (다음 사람 전진 가속)
                try {
                    queueStore.releasePass(sid, uid);
                } catch (Exception ignored) {}

                Long ownerAfter = null;
                try {
                    ownerAfter = seatLockStore.getLockOwner(sid, sn);
                } catch (Exception ignored) {}

                boolean ownerIsMeAfter = ownerAfter != null && ownerAfter.equals(uid);
                boolean shouldPublishRelease = canceled || ownerIsMeAfter;

                try {
                    seatLockStore.releaseSeat(sid, sn, uid);
                } catch (Exception ignored) {}

                if (shouldPublishRelease) {
                    try {
                        seatSseHub.publish(
                                sid,
                                new SeatChangedEvent("RELEASED", sid, sn, false, uid, LocalDateTime.now())
                        );
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void confirmSeat(Long scheduleId, String seatNo, Long userId) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        final Long sid = scheduleId;
        final Long uid = userId;
        final String sn = seatNo.trim().toUpperCase();

        Long owner = null;
        try {
            owner = seatLockStore.getLockOwner(sid, sn);
        } catch (Exception ignored) {}

        if (owner == null) {
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        }
        if (!owner.equals(uid)) {
            throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        }

        reservationService.confirm(uid, sid, sn);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // pass 회수 (다음 사람 전진 가속)
                try {
                    queueStore.releasePass(sid, uid);
                } catch (Exception ignored) {}

                try {
                    seatLockStore.releaseSeat(sid, sn, uid);
                } catch (Exception ignored) {}

                try {
                    seatSseHub.publish(
                            sid,
                            new SeatChangedEvent("CONFIRMED", sid, sn, true, uid, LocalDateTime.now())
                    );
                } catch (Exception ignored) {}
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
