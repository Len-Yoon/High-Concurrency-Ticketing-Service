package com.len.ticketing.infra.reservation;

import com.len.ticketing.domain.reservation.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationJpaRepository extends JpaRepository<Reservation, Long> {

    // 유저 예매 목록 조회
    List<Reservation> findByUserId(Long userId);

    // 같은 (scheduleId, seatNo) 가 이미 존재하는지
    boolean existsByScheduleIdAndSeatNo(Long scheduleId, String seatNo);
}
