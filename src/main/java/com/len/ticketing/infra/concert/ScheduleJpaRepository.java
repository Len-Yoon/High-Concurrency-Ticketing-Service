package com.len.ticketing.infra.concert;

import com.len.ticketing.domain.concert.Schedule;
import com.len.ticketing.domain.concert.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleJpaRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByConcertIdOrderByShowAtAsc(Long concertId);

    Optional<Seat> findByScheduleIdAndSeatNo(Long scheduleId, String seatNo);
}