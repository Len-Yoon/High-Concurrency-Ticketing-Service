package com.len.ticketing.api.reservation.dto;

public record ReservationRequest(
        Long userId,
        Long scheduleId,
        String seatNo
) {
}
