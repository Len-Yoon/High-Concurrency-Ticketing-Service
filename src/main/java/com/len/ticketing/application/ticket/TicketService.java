package com.len.ticketing.application.ticket;

import com.len.ticketing.domain.concert.Seat;
import com.len.ticketing.domain.ticket.SeatLockStore;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class TicketService {

    private static final long SEAT_LOCK_TTL_SECONDS = 300L; // 5분

    private final SeatJpaRepository seatRepository;
    private final SeatLockStore seatLockStore;

    @Transactional(readOnly = true)
    public HoldSeatResult holdSeat(long scheduleId, String seatNo, long userId) {
        // 1. 좌석 존재 여부 체크
        Seat seat = seatRepository.findByScheduleIdAndSeatNo(scheduleId, seatNo)
                .orElseThrow(() -> new IllegalArgumentException("좌석이 존재하지 않습니다."));

        // 2. 좌석 락 시도
        boolean locked = seatLockStore.lockSeat(scheduleId, seatNo, userId, SEAT_LOCK_TTL_SECONDS);
        if (!locked) {
            Long owner = seatLockStore.getLockOwner(scheduleId, seatNo);
            if (owner != null && owner != userId) {
                return new HoldSeatResult(false, "이미 다른 사용자가 선점한 좌석입니다.");
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
