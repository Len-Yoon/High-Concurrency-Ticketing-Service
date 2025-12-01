package com.len.ticketing.api.queue.dto;

public record QueueEnterRequest(
        Long scheduleId,
        Long userId
) {}
