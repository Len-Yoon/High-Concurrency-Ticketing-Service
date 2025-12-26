package com.len.ticketing.application.seat;

import com.len.ticketing.api.seat.dto.SeatStatusResponse;
import com.len.ticketing.domain.concert.Seat;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SeatQueryService {

    private final SeatJpaRepository seatRepository;
    private final ReservationJpaRepository reservationRepository;

    /**
     * 특정 스케줄의 전체 좌석 + 예매 여부 조회
     */
    @Transactional(readOnly = true)
    public List<SeatStatusResponse> getSeatStatus(Long scheduleId) {

        // 1) 해당 스케줄의 전체 좌석 (좌석 번호 오름차순)
        List<Seat> seats = seatRepository.findBySchedule_IdOrderBySeatNoAsc(scheduleId);

        // 2) 해당 스케줄에서 이미 예매된 좌석 번호 목록
        List<String> reservedSeatNos =
                reservationRepository.findActiveSeatNos(scheduleId, LocalDateTime.now());
        Set<String> reservedSet = new HashSet<>(reservedSeatNos);

        // 3) seat + reserved 여부 묶어서 반환
        return seats.stream()
                .map(seat -> new SeatStatusResponse(
                        seat.getId(),
                        seat.getSeatNo(),
                        seat.getPrice(),
                        reservedSet.contains(seat.getSeatNo())
                ))
                .toList();
    }
}
