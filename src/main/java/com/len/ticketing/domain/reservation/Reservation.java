package com.len.ticketing.domain.reservation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_no", nullable = false, length = 255)
    private String seatNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // active=1이면 점유중, NULL이면 해제됨(취소/만료)
    @Column(name = "active")
    private Integer active;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static Reservation newHold(Long userId, Long scheduleId, String seatNo,
                                      LocalDateTime now, Duration ttl) {
        Reservation r = new Reservation();
        r.userId = userId;
        r.scheduleId = scheduleId;
        r.seatNo = seatNo;
        r.status = ReservationStatus.HELD;
        r.expiresAt = now.plus(ttl);
        r.active = 1;
        return r;
    }

    public boolean isExpired(LocalDateTime now) {
        return status == ReservationStatus.HELD
                && expiresAt != null
                && expiresAt.isBefore(now);
    }

    public void confirm(LocalDateTime now) {
        this.status = ReservationStatus.CONFIRMED;
        this.expiresAt = null;
        this.active = 1;
        this.updatedAt = now;
    }

    public void cancel(LocalDateTime now) {
        this.status = ReservationStatus.CANCELLED;
        this.active = null;
        this.updatedAt = now;
    }

    public void expire(LocalDateTime now) {
        this.status = ReservationStatus.EXPIRED;
        this.active = null;
        this.updatedAt = now;
    }
}
