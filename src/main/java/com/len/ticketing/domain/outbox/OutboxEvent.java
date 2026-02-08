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

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry;

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
        e.maxRetry = 10;
        e.lastError = null;
        return e;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.lastError = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void markRetryOrFail(String errorMessage) {
        this.retryCount += 1;
        this.lastError = (errorMessage == null) ? null :
                (errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage);

        if (this.retryCount >= this.maxRetry) {
            this.status = OutboxStatus.FAILED;
            this.updatedAt = LocalDateTime.now();
            return;
        }

        int backoff = Math.min(60, (int) Math.pow(2, Math.min(6, this.retryCount))); // 2,4,8...60
        this.status = OutboxStatus.PENDING;
        this.nextRetryAt = LocalDateTime.now().plusSeconds(backoff);
        this.updatedAt = LocalDateTime.now();
    }

}
