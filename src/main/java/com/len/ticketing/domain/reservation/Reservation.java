package com.len.ticketing.domain.reservation;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @Column(name="schedule_id", nullable=false)
    private Long scheduleId;

    @Column(name="seat_no", nullable=false)
    private String seatNo;

    @Enumerated(EnumType.STRING)
    @Column(name="status", nullable=false)
    private ReservationStatus status;

    @Column(name="expires_at")
    private LocalDateTime expiresAt;

    @Column(name="active")
    private Integer active; // 1=활성, NULL=비활성

    @Column(name="created_at", updatable=false)
    private LocalDateTime createdAt;

    @Column(name="updated_at")
    private LocalDateTime updatedAt;

    public static Reservation newHold(Long userId, Long scheduleId, String seatNo,
                                      LocalDateTime now, Duration ttl) {
        Reservation r = new Reservation();
        r.userId = userId;
        r.scheduleId = scheduleId;
        r.seatNo = normalize(seatNo);
        r.status = ReservationStatus.HELD;
        r.expiresAt = now.plus(ttl);
        r.active = 1;            // ✅ 이거 없으면 active=NULL로 들어감
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    public void confirm(LocalDateTime now) {
        this.status = ReservationStatus.CONFIRMED;
        this.expiresAt = null;
        this.active = 1;
        this.updatedAt = now;
    }

    public void expire(LocalDateTime now) {
        this.status = ReservationStatus.EXPIRED;
        this.active = null;
        this.updatedAt = now;
    }

    private static String normalize(String raw) {
        return raw == null ? null : raw.trim().toUpperCase();
    }
}
