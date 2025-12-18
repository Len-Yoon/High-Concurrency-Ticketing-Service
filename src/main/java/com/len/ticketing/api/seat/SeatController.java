package com.len.ticketing.api.seat;

import com.len.ticketing.api.seat.dto.SeatStatusResponse;
import com.len.ticketing.application.seat.SeatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/seats")
public class SeatController {

    private final SeatQueryService seatQueryService;

    /**
     * 특정 스케줄의 전체 좌석 상태 조회
     * GET /api/seats?scheduleId=1
     */
    @GetMapping
    public List<SeatStatusResponse> getSeatStatus(@RequestParam Long scheduleId) {
        return seatQueryService.getSeatStatus(scheduleId);
    }

    /**
     * (옵션) 예매 가능한 좌석만 보고 싶으면 이런 것도 추가 가능
     * GET /api/seats/available?scheduleId=1
     */
    @GetMapping("/available")
    public List<SeatStatusResponse> getAvailableSeats(@RequestParam Long scheduleId) {
        return seatQueryService.getSeatStatus(scheduleId).stream()
                .filter(seat -> !seat.reserved())
                .toList();
    }
}
