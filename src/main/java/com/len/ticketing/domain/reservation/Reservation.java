package com.len.ticketing.domain.reservation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "reservation",
        indexes = {
                @Index(name = "idx_reservation_schedule_seat_active", columnList = "schedule_id,seat_no,active"),
                @Index(name = "idx_reservation_expire_scan", columnList = "status,active,expires_at")
        }
)
public class Reservation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_no", nullable = false, length = 20)
    private String seatNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    // 1=active, 0=inactive
    @Column(name = "active", nullable = false)
    private Integer active;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static Reservation newHold(Long userId, Long scheduleId, String seatNo,
                                      LocalDateTime now, Duration ttl) {
        Reservation r = new Reservation();
        r.userId = userId;
        r.scheduleId = scheduleId;
        r.seatNo = seatNo;

        r.status = ReservationStatus.HELD;
        r.active = 1;                 // ✅ 이거 없으면 findActiveForUpdate에 안 잡힘
        r.expiresAt = now.plus(ttl);

        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    public void confirm(LocalDateTime now) {
        this.status = ReservationStatus.CONFIRMED;
        this.updatedAt = now;
        // 보통 CONFIRMED는 active=1 그대로 둬도 됨
    }

    public void expire(LocalDateTime now) {
        this.status = ReservationStatus.EXPIRED;
        this.active = 0;              // ✅ null 말고 0으로 내림
        this.updatedAt = now;
    }

    public void cancel(LocalDateTime now) {
        this.status = ReservationStatus.CANCELLED;
        this.active = 0;
        this.updatedAt = now;
    }

    public boolean isValidHold(LocalDateTime now) {
        return this.active == 1
                && this.status == ReservationStatus.HELD
                && this.expiresAt != null
                && this.expiresAt.isAfter(now);
    }
}
