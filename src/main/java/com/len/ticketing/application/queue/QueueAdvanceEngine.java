package com.len.ticketing.application.queue;

public interface QueueAdvanceEngine {
    int advance(long scheduleId, long nowMs, int capacity, long passTtlSeconds);
    String name(); // "lua" | "java"
}
