package com.len.ticketing.application.confirm;

import java.time.Instant;

public record ConfirmRequestedPayload(
        String eventId,
        Long scheduleId,
        String seatNo,
        Long userId,
        Instant requestedAt
) {}