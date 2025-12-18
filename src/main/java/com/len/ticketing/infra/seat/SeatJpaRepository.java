package com.len.ticketing.infra.seat;

import com.len.ticketing.domain.seat.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatJpaRepository extends JpaRepository<Seat, Long> {

    // 특정 스케줄의 전체 좌석 조회
    List<Seat> findByScheduleId(Long scheduleId);
}
