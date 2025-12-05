package com.len.ticketing.application.ticket;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.concert.Seat;
import com.len.ticketing.domain.queue.QueueStore;
import com.len.ticketing.domain.ticket.SeatLockStore;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class TicketService {

    // 좌석 락 유지 시간 (초)
    private static final long SEAT_LOCK_TTL_SECONDS = 300L; // 5분

    // 대기열 상위 몇 명까지 입장 허용할지
    private static final long ALLOWED_QUEUE_RANK = 100L;

    private final SeatJpaRepository seatRepository;
    private final SeatLockStore seatLockStore;
    private final QueueStore queueStore;

    @Transactional(readOnly = true)
    public HoldSeatResult holdSeat(long scheduleId, String seatNo, long userId) {

        // 0. 대기열 체크 (상위 ALLOWED_QUEUE_RANK 안에 있어야 함)
        boolean canEnter = queueStore.canEnter(scheduleId, userId, ALLOWED_QUEUE_RANK);
        if (!canEnter) {
            return new HoldSeatResult(false, "대기열 순번이 아직 입장 가능 범위가 아닙니다.");
        }

        // 1. 좌석 존재 여부 체크
        // before
//        Seat seat = seatRepository.findByScheduleIdAndSeatNo(scheduleId, seatNo)
//                .orElseThrow(() -> new IllegalArgumentException("좌석이 존재하지 않습니다."));

        // after
        Seat seat = seatRepository.findByScheduleIdAndSeatNo(scheduleId, seatNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        // 2. 좌석 락 시도
        boolean locked = seatLockStore.lockSeat(scheduleId, seatNo, userId, SEAT_LOCK_TTL_SECONDS);
        if (!locked) {
            Long owner = seatLockStore.getLockOwner(scheduleId, seatNo);
            //before
//            if (owner != null && owner != userId) {
//                return new HoldSeatResult(false, "이미 다른 사용자가 선점한 좌석입니다.");

            // after
            if (owner != null && !owner.equals(userId)) {
                throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
            } else {
                return new HoldSeatResult(false, "좌석 선점에 실패했습니다.");
            }
        }

        return new HoldSeatResult(true, "좌석 선점에 성공했습니다. 결제를 진행해주세요.");
    }

    @Transactional
    public void releaseSeat(long scheduleId, String seatNo, long userId) {
        seatLockStore.releaseSeat(scheduleId, seatNo, userId);
    }

    public record HoldSeatResult(boolean success, String message) {}
}
