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
    public Reservation hold(Long userId, Long scheduleId, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        // 좌석 존재 확인
        Seat seat = seatRepository.findByScheduleIdAndSeatNo(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        // 만료 처리 한번 돌리고(선택)
        reservationRepository.expireAll(now);

        // active=1 row 락 조회
        var existingOpt = reservationRepository.findActiveForUpdate(scheduleId, sn);
        if (existingOpt.isPresent()) {
            var existing = existingOpt.get();

            if (existing.getStatus() == ReservationStatus.CONFIRMED) {
                throw new BusinessException(ErrorCode.ALREADY_RESERVED);
            }

            if (existing.getStatus() == ReservationStatus.HELD && existing.isValidHold(now)) {
                throw new BusinessException(ErrorCode.ALREADY_HELD);
            }

            // 만료된 hold면 inactive 처리
            existing.expire(now);
        }

        Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
        return reservationRepository.save(hold);
    }

    @Transactional
    public void confirm(Long userId, Long scheduleId, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        var r = reservationRepository.findActiveForUpdate(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOLD_NOT_FOUND));

        if (!r.getUserId().equals(userId)) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (r.getStatus() != ReservationStatus.HELD) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);

        if (r.getExpiresAt() != null && r.getExpiresAt().isBefore(now)) {
            r.expire(now);
            throw new BusinessException(ErrorCode.HOLD_EXPIRED);
        }

        r.confirm(now);
    }

    @Transactional
    public void assertValidHoldOwner(Long userId, Long scheduleId, String seatNo) {
        LocalDateTime now = LocalDateTime.now();
        String sn = seatNo.trim().toUpperCase();

        var r = reservationRepository.findActiveForUpdate(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOLD_NOT_FOUND));

        if (!r.getUserId().equals(userId)) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (r.getStatus() != ReservationStatus.HELD) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);

        if (r.getExpiresAt() != null && r.getExpiresAt().isBefore(now)) {
            r.expire(now);
            throw new BusinessException(ErrorCode.HOLD_EXPIRED);
        }
    }
}
