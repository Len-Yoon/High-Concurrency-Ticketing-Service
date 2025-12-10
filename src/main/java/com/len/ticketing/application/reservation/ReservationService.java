// src/main/java/com/len/ticketing/application/reservation/ReservationService.java
package com.len.ticketing.application.reservation;

import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationJpaRepository reservationRepository;

    @Transactional
    public Reservation hold(Long userId, Long scheduleId, String seatNo) {

        // 1차 방어: 이미 예약된 좌석인지 확인
        if (reservationRepository.existsByScheduleIdAndSeatNo(scheduleId, seatNo)) {
            throw new IllegalStateException("이미 예약된 좌석입니다.");
        }

        // 너 엔티티에 맞게 생성 방식만 맞춰주면 됨
        Reservation reservation = Reservation.create(userId, scheduleId, seatNo);
        // create 없으면 new Reservation(...)로 맞춰서 쓰면 되고

        try {
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            // 동시성으로 한 번 더 들어온 애들
            throw new IllegalStateException("이미 예약된 좌석입니다.");
        }
    }
}
