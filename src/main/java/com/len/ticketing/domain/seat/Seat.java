package com.len.ticketing.domain.seat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 스케줄의 좌석인지
    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    // "A-1", "A-2" 같은 좌석 번호
    @Column(name = "seat_no", nullable = false, length = 20)
    private String seatNo;

    // 좌석 가격
    @Column(name = "price", nullable = false)
    private int price;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // 생성용 팩토리 메서드
    public static Seat create(Long scheduleId, String seatNo, int price) {
        Seat seat = new Seat();
        seat.scheduleId = scheduleId;
        seat.seatNo = seatNo;
        seat.price = price;
        seat.createdAt = LocalDateTime.now();
        return seat;
    }
}
