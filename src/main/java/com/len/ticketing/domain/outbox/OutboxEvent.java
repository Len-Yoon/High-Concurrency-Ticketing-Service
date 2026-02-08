package com.len.ticketing.domain.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @Column(name = "event_id", length = 64, nullable = false, updatable = false)
    private String eventId;

    @Column(name = "topic", length = 120, nullable = false)
    private String topic;

    @Column(name = "event_key", length = 120, nullable = false)
    private String eventKey;

    @Lob
    @Column(name = "payload", columnDefinition = "json", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

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
        e.maxRetry = 10;
        e.nextRetryAt = now;
        e.lastError = null;
        e.publishedAt = null;

        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void markPublished() {
        LocalDateTime now = LocalDateTime.now();
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * 실패 시 재시도 스케줄링.
     * - retry_count 증가
     * - max_retry 이상이면 FAILED 전환
     * - 아니면 PENDING 유지 + next_retry_at backoff
     */
    public void markRetryOrFail(String errorMessage) {
        LocalDateTime now = LocalDateTime.now();

        this.retryCount += 1;
        this.lastError = trim500(errorMessage);

        if (this.retryCount >= this.maxRetry) {
            this.status = OutboxStatus.FAILED;
            this.updatedAt = now;
            return;
        }

        int backoffSeconds = Math.min(60, (int) Math.pow(2, Math.min(6, this.retryCount))); // 2,4,8,16,32,60...
        this.status = OutboxStatus.PENDING;
        this.nextRetryAt = now.plusSeconds(backoffSeconds);
        this.updatedAt = now;
    }

    public void setMaxRetry(int maxRetry) {
        if (maxRetry > 0) {
            this.maxRetry = maxRetry;
        }
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
        if (this.nextRetryAt == null) this.nextRetryAt = now;
        if (this.status == null) this.status = OutboxStatus.PENDING;
        if (this.maxRetry <= 0) this.maxRetry = 10;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    private String trim500(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
