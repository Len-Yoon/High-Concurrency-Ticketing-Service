package com.len.ticketing.domain.concert;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "seat",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_seat_schedule_seat",
                        columnNames = {"schedule_id", "seat_no"}
                )
        }
)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 단방향 ManyToOne
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Column(name = "seat_no", nullable = false, length = 20)
    private String seatNo;

    @Column(nullable = false)
    private int price;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private Seat(Schedule schedule, String seatNo, int price) {
        this.schedule = schedule;
        this.seatNo = seatNo;
        this.price = price;
        this.createdAt = LocalDateTime.now();
    }

    public static Seat create(Schedule schedule, String seatNo, int price) {
        return new Seat(schedule, seatNo, price);
    }
}
