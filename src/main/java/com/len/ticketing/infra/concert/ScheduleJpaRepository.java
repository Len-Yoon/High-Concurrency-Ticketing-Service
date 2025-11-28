package com.len.ticketing.infra.concert;

import com.len.ticketing.domain.concert.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleJpaRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByConcertIdOrderByShowAtAsc(Long concertId);
}