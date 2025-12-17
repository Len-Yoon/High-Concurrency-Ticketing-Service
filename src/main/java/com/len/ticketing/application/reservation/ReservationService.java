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

    /**
     * 좌석 홀드 / 예약
     * - 같은 (scheduleId, seatNo)에 대해 중복 예약 방지
     */
    @Transactional
    public Reservation hold(Long userId, Long scheduleId, String seatNo) {

        // 1차 방어: 이미 예약된 좌석인지 체크
        if (reservationRepository.existsByScheduleIdAndSeatNo(scheduleId, seatNo)) {
            throw new IllegalStateException("이미 예약된 좌석입니다.");
        }

        // 엔티티 생성
        Reservation reservation = Reservation.create(userId, scheduleId, seatNo);

        try {
            // 2차 방어: 유니크 제약 위반 (동시성) → 예외 변환
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            throw new SeatAlreadyReservedException("이미 예약된 좌석입니다.");
        }
    }
}
