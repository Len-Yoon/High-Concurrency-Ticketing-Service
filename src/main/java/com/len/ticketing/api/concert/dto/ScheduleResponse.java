package com.len.ticketing.api.concert.dto;

import com.len.ticketing.domain.concert.Schedule;

import java.time.LocalDateTime;

public record ScheduleResponse(
        Long id,
        Long concertId,
        LocalDateTime showAt
) {
    public static ScheduleResponse from(Schedule schedule) {
        return new ScheduleResponse(
                schedule.getId(),
                schedule.getConcert().getId(),
                schedule.getShowAt()
        );
    }
}
