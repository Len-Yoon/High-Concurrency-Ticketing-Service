package com.len.ticketing.infra.reservation;

import com.len.ticketing.domain.reservation.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationJpaRepository extends JpaRepository<Reservation, Long> {

    // 조회 메서드 추가
    List<Reservation> findByUserId(Long userId);
}
