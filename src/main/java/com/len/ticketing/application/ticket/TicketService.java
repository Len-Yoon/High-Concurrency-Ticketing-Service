package com.len.ticketing.application.ticket;

import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.queue.QueueStore;
import com.len.ticketing.domain.ticket.SeatLockStore;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.sse.SeatChangedEvent;
import com.len.ticketing.infra.sse.SeatSseHub;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;

@Service
public class TicketService {

    private static final long SEAT_LOCK_TTL_SECONDS = 300L; // 5분
    private static final String UK_RESERVATION_ACTIVE_SEAT = "uk_reservation_active_seat";

    private final SeatJpaRepository seatRepository;
    private final QueueStore queueStore;
    private final SeatLockStore seatLockStore;
    private final ReservationService reservationService;
    private final SeatSseHub seatSseHub;

    public TicketService(
            SeatJpaRepository seatRepository,
            QueueStore queueStore,
            SeatLockStore seatLockStore,
            ReservationService reservationService,
            SeatSseHub seatSseHub
    ) {
        this.seatRepository = seatRepository;
        this.queueStore = queueStore;
        this.seatLockStore = seatLockStore;
        this.reservationService = reservationService;
        this.seatSseHub = seatSseHub;
    }

    @Value("${ticketing.queue.enabled:true}")
    private boolean queueEnabled;

    public HoldResult holdSeat(Long scheduleId, String seatNo, Long userId, boolean bypassQueue, String queueToken) {
        long t0 = System.nanoTime();

        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        long t1 = System.nanoTime();

        // Queue Gate
        if (queueEnabled && !bypassQueue) {
            if (queueToken == null || queueToken.isBlank()) {
                if (queueStore.getPosition(scheduleId, userId) == -1L) {
                    queueStore.enterQueue(scheduleId, userId);
                }
                throw new BusinessException(ErrorCode.QUEUE_NOT_ALLOWED);
            }

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
                    // ReservationService.hold(...) returns Reservation
                    var saved = reservationService.hold(userId, scheduleId, sn);
                    Long reservationId = saved.getId();

                    publishAfterCommit(
                            scheduleId,
                            new SeatChangedEvent("HELD", scheduleId, sn, true, userId, LocalDateTime.now())
                    );

                    return new HoldResult(true, "좌석 선점에 성공했습니다. 결제를 진행해주세요.", reservationId);

                } catch (DataIntegrityViolationException e) {
                    // ✅ DB 유니크 가드(좌석당 active=1 1건) 충돌 → 정상 경쟁 상황이므로 409로 변환
                    if (isUkReservationActiveSeat(e)) {
                        throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
                    }
                    throw e;

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

            return new HoldResult(false, "좌석 선점에 실패했습니다.", null);

        } catch (RuntimeException e) {
            // 락은 항상 정리 (BusinessException 포함)
            seatLockStore.releaseSeat(scheduleId, sn, userId);
            throw e;
        }
    }

    private boolean isUkReservationActiveSeat(Throwable e) {
        // 1) Hibernate ConstraintViolationException constraintName 기준
        Throwable t = e;
        while (t != null) {
            if (t instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.equalsIgnoreCase(UK_RESERVATION_ACTIVE_SEAT)) {
                    return true;
                }
            }
            t = t.getCause();
        }

        // 2) fallback: 메시지 기반 (constraintName이 null로 오는 케이스 대비)
        String msg = e.getMessage();
        return msg != null && msg.contains(UK_RESERVATION_ACTIVE_SEAT);
    }

    public HoldResult holdSeatById(
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

    public record HoldResult(
            boolean success,
            String message,
            Long reservationId
    ) {}

    @PostConstruct
    public void init() {
        System.out.println("ACTIVE ticketing.queue.enabled = " + queueEnabled);
    }
}