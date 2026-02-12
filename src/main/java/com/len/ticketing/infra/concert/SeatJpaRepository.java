package com.len.ticketing.infra.concert;

import com.len.ticketing.domain.concert.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeatJpaRepository extends JpaRepository<Seat, Long> {

    List<Seat> findBySchedule_IdOrderBySeatNoAsc(Long scheduleId);

    Optional<Seat> findBySchedule_IdAndSeatNo(Long scheduleId, String seatNo);

    boolean existsBySchedule_IdAndSeatNo(Long scheduleId, String seatNo);

    Optional<Seat> findBySchedule_IdAndId(Long scheduleId, Long seatId);

    default Optional<String> findSeatNoByScheduleIdAndSeatId(Long scheduleId, Long seatId) {
        return findBySchedule_IdAndId(scheduleId, seatId).map(Seat::getSeatNo);
    }
}
