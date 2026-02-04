package com.len.ticketing.api.queue.dto;

public record QueueStatusResponse(
        long position,        // canEnter면 0
        boolean canEnter,
        String token,         // canEnter면 내려줌
        Long expiresAt        // epoch ms
) {}
