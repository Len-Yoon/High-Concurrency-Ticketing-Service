package com.len.ticketing.api.seat.dto;

public record SeatStatusResponse(
        Long seatId,
        String seatNo,
        int price,
        boolean reserved
) {
}
