package com.len.ticketing.api.reservation.dto;

import java.time.LocalDateTime;

public record ReservationResponse(
        Long id,
        Long userId,
        Long scheduleId,
        String seatNo,
        LocalDateTime createdAt
) {}
