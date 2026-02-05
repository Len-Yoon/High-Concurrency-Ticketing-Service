package com.len.ticketing.application.confirm;

import java.time.LocalDateTime;

public record ConfirmRequestedPayload(
        String eventId,
        Long scheduleId,
        String seatNo,
        Long userId,
        LocalDateTime requestedAt
) {}
