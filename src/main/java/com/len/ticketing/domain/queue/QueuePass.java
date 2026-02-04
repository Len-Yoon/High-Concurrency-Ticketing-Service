package com.len.ticketing.domain.queue;

public record QueuePass(String token, long expiresAtEpochMs) {}
