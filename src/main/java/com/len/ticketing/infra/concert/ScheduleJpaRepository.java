package com.len.ticketing.infra.concert;

import com.len.ticketing.domain.concert.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleJpaRepository extends JpaRepository<Schedule, Long> {
    /**
     * Schedule 엔티티는 concertId 필드가 아니라 Concert(concert) 연관관계를 갖습니다.
     */
    List<Schedule> findByConcert_IdOrderByShowAtAsc(Long concertId);
}