package com.len.ticketing.application.queue;

public interface QueueAdvanceEngine {
    int advance(long scheduleId, long nowMs, int capacity, int passTtlSeconds);
    String name(); // "lua" | "java"
}
