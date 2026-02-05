package com.len.ticketing.domain.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(nullable = false, length = 120)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 120)
    private String eventKey;

    @Lob
    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static OutboxEvent pending(String eventId, String topic, String eventKey, String payload) {
        OutboxEvent e = new OutboxEvent();
        LocalDateTime now = LocalDateTime.now();
        e.eventId = eventId;
        e.topic = topic;
        e.eventKey = eventKey;
        e.payload = payload;
        e.status = OutboxStatus.PENDING;
        e.retryCount = 0;
        e.nextRetryAt = now;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markRetry(int backoffSeconds) {
        this.status = OutboxStatus.PENDING;
        this.retryCount += 1;
        this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
        this.updatedAt = LocalDateTime.now();
    }
}
