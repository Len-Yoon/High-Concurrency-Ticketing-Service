package com.len.ticketing.api.queue.dto;

public record QueueStatusResponse(
        long position,
        boolean canEnter
) {}
