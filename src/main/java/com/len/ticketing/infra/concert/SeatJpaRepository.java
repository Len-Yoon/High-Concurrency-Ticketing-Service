package com.len.ticketing.infra.concert;

import com.len.ticketing.domain.concert.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatJpaRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByScheduleIdOrderBySeatNoAsc(Long scheduleId);
}
