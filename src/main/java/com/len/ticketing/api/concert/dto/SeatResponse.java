package com.len.ticketing.api.concert.dto;

import com.len.ticketing.domain.concert.Seat;

public record SeatResponse(
        Long id,
        Long scheduleId,
        String seatNo,
        int price
) {
    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSchedule().getId(),
                seat.getSeatNo(),
                seat.getPrice()
        );
    }
}
