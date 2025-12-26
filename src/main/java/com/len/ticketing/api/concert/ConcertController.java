package com.len.ticketing.api.concert;

import com.len.ticketing.api.concert.dto.ConcertResponse;
import com.len.ticketing.api.concert.dto.ScheduleResponse;
import com.len.ticketing.api.concert.dto.SeatResponse;
import com.len.ticketing.infra.concert.ConcertJpaRepository;
import com.len.ticketing.infra.concert.ScheduleJpaRepository;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ConcertController {
    private final ConcertJpaRepository concertRepository;
    private final ScheduleJpaRepository scheduleRepository;
    private final SeatJpaRepository seatRepository;

    // 1) 공연 목록
    @GetMapping("/concerts")
    public List<ConcertResponse> getConcerts() {
        return concertRepository.findAll()
                .stream()
                .map(ConcertResponse::from)
                .toList();
    }

    // 2) 공연 회차 목록
    @GetMapping("/concerts/{concertId}/schedules")
    public List<ScheduleResponse> getSchedules(@PathVariable Long concertId) {
        return scheduleRepository.findByConcert_IdOrderByShowAtAsc(concertId)
                .stream()
                .map(ScheduleResponse::from)
                .toList();
    }

    // 3) 회차별 좌석 목록
    @GetMapping("/schedules/{scheduleId}/seats")
    public List<SeatResponse> getSeats(@PathVariable Long scheduleId) {
        return seatRepository.findBySchedule_IdOrderBySeatNoAsc(scheduleId)
                .stream()
                .map(SeatResponse::from)
                .toList();
    }
}
