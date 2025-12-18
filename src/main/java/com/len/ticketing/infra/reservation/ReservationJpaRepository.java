package com.len.ticketing.infra.reservation;

import com.len.ticketing.domain.reservation.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReservationJpaRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByUserId(Long userId);

    boolean existsByScheduleIdAndSeatNo(Long scheduleId, String seatNo);

    // 특정 스케줄에서 이미 예매된 좌석 번호들만 조회
    @Query("select r.seatNo from Reservation r where r.scheduleId = :scheduleId")
    List<String> findSeatNosByScheduleId(@Param("scheduleId") Long scheduleId);
}
