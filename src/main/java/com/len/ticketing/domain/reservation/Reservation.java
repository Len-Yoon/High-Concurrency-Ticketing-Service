package com.len.ticketing.domain.reservation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "reservation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_schedule_seat",
                        columnNames = {"schedule_id", "seat_no"}
                )
        }
)
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

    @Column(name = "seat_no", nullable = false)
    private String seatNo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private Reservation(Long userId, Long scheduleId, String seatNo) {
        this.userId = userId;
        this.scheduleId = scheduleId;
        this.seatNo = seatNo;
        this.createdAt = LocalDateTime.now();
    }

    public static Reservation create(Long userId, Long scheduleId, String seatNo) {
        return new Reservation(userId, scheduleId, seatNo);
    }
}
