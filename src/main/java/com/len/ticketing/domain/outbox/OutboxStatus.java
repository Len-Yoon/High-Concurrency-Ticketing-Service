package com.len.ticketing.domain.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}