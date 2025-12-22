package com.len.ticketing.application.reservation;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.concert.Seat;
import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.domain.reservation.ReservationStatus;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final SeatJpaRepository seatRepository;
    private final ReservationJpaRepository reservationRepository;

    private static final Duration HOLD_TTL = Duration.ofMinutes(3);

    @Transactional
    public void hold(Long userId, Long scheduleId, String seatNo) {
        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        // 좌석 존재 확인
        Seat seat = seatRepository.findByScheduleIdAndSeatNo(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        // active=1 row 락 조회
        var existingOpt = reservationRepository.findActiveForUpdate(scheduleId, sn);
        if (existingOpt.isPresent()) {
            var existing = existingOpt.get();

            // 이미 확정이면 막기
            if (existing.getStatus() == ReservationStatus.CONFIRMED) {
                throw new IllegalStateException("이미 예매된 좌석");
            }

            // HELD + 아직 만료 전이면 막기
            if (existing.getStatus() == ReservationStatus.HELD
                    && existing.getExpiresAt() != null
                    && existing.getExpiresAt().isAfter(now)) {
                throw new IllegalStateException("이미 홀드된 좌석");
            }

            // 만료된 HELD면 expire 처리 후 새 hold 생성
            existing.expire(now);
        }

        reservationRepository.save(Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL));
    }

    @Transactional
    public void confirm(Long userId, Long scheduleId, String seatNo) {
        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        var r = reservationRepository.findActiveForUpdate(scheduleId, sn)
                .orElseThrow(() -> new IllegalStateException("홀드 없음"));

        if (!r.getUserId().equals(userId)) throw new IllegalStateException("홀드 없음");
        if (r.getStatus() != ReservationStatus.HELD) throw new IllegalStateException("홀드 없음");
        if (r.getExpiresAt() != null && r.getExpiresAt().isBefore(now)) {
            r.expire(now);
            throw new IllegalStateException("홀드 없음");
        }

        r.confirm(now);
    }
}
