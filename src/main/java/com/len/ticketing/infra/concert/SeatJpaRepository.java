package com.len.ticketing.infra.concert;

import com.len.ticketing.domain.concert.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeatJpaRepository extends JpaRepository<Seat, Long> {

    /**
     * Seat 엔티티는 scheduleId 필드가 아니라 Schedule(schedule) 연관관계를 갖습니다.
     * Spring Data JPA 파생쿼리에서 schedule.id 를 타려면 schedule_Id 형태로 써야 합니다.
     */
    List<Seat> findBySchedule_IdOrderBySeatNoAsc(Long scheduleId);

    Optional<Seat> findBySchedule_IdAndSeatNo(Long scheduleId, String seatNo);

    boolean existsBySchedule_IdAndSeatNo(Long scheduleId, String seatNo);
}
