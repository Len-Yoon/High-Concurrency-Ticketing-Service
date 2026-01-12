package com.len.ticketing.infra.sse;

import java.time.LocalDateTime;

public record SeatChangedEvent(
        String type,          // "HELD" / "RELEASED" / "EXPIRED" / "CONFIRMED"
        Long scheduleId,
        String seatNo,
        Boolean reserved,     // true/false
        Long userId,          // 없으면 null
        LocalDateTime occurredAt
) {}
