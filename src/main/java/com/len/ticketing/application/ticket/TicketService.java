package com.len.ticketing.application.ticket;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.domain.queue.QueueStore;
import com.len.ticketing.domain.ticket.SeatLockStore;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class TicketService {

    private static final long SEAT_LOCK_TTL_SECONDS = 300L; // 5분
    private static final long ALLOWED_QUEUE_RANK = 100L;

    private final SeatJpaRepository seatRepository;
    private final QueueStore queueStore;
    private final SeatLockStore seatLockStore;
    private final ReservationService reservationService;

    @Transactional
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

        boolean seatExists = seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn);
        if (!seatExists) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

        boolean locked = seatLockStore.lockSeat(scheduleId, sn, userId, SEAT_LOCK_TTL_SECONDS);
        if (!locked) {
            Long owner = seatLockStore.getLockOwner(scheduleId, sn);
            if (owner != null && !owner.equals(userId)) {
                throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
            }
            return new HoldSeatResult(false, "좌석 선점에 실패했습니다.");
        }

        // Redis 락을 잡은 상태에서 DB에 hold row를 만든다.
        try {
            reservationService.hold(userId, scheduleId, sn);
            return new HoldSeatResult(true, "좌석 선점에 성공했습니다. 결제를 진행해주세요.");
        } catch (RuntimeException e) {
            // DB hold가 실패하면 Redis 락도 풀어야 다음 유저가 시도 가능
            seatLockStore.releaseSeat(scheduleId, sn, userId);
            throw e;
        }
    }

    // ✅ TicketController가 찾는 시그니처( Long, String, Long ) 맞춰서 추가
    @Transactional
    public void releaseSeat(Long scheduleId, String seatNo, Long userId) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String sn = seatNo.trim().toUpperCase();
        // 1) Redis 락 해제 (idempotent)
        seatLockStore.releaseSeat(scheduleId, sn, userId);

        // 2) DB hold 취소 (idempotent하게 HOLD_NOT_FOUND는 무시)
        try {
            reservationService.cancelHold(userId, scheduleId, sn);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.HOLD_NOT_FOUND) {
                throw e;
            }
        }
    }

    public record HoldSeatResult(boolean success, String message) {}
}
