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

        // 0) 파라미터 기본 검증 (null 방지)
        if (userId == null || scheduleId == null || seatNo == null) {
            throw new IllegalArgumentException("userId, scheduleId, seatNo는 null일 수 없습니다.");
        }

        // 1차: 이미 예매된 좌석인지 체크
        if (reservationRepository.existsByScheduleIdAndSeatNo(scheduleId, seatNo)) {
            // 여기서 IllegalStateException 던지면 무조건 INTERNAL_ERROR로 감
            throw new SeatAlreadyReservedException("이미 예매된 좌석입니다. (사전체크)");
        }

        Reservation reservation = Reservation.create(userId, scheduleId, seatNo);

        try {
            // 2차: 동시성 상황에서 유니크 제약 위반 날 수 있음
            return reservationRepository.save(reservation);
        } catch (DataIntegrityViolationException e) {
            // DB가 한 번 더 막아준 케이스 → 이것도 중복 예매로 통일
            throw new SeatAlreadyReservedException("이미 예매된 좌석입니다. (DB 제약 위반)");
        }
    }
}
