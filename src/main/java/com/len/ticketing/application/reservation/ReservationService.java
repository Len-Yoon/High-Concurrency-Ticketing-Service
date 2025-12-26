package com.len.ticketing.application.reservation;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.domain.reservation.ReservationStatus;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final SeatJpaRepository seatRepository;
    private final ReservationJpaRepository reservationRepository;

    // Redis 좌석 락 TTL과 동일하게 가져가는게 안전(락/홀드 불일치 방지)
    private static final Duration HOLD_TTL = Duration.ofMinutes(5);

    @Transactional
    public Reservation hold(Long userId, Long scheduleId, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        // 좌석 존재 확인
        boolean seatExists = seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn);
        if (!seatExists) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

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
            // ⚠️ 같은 트랜잭션에서 기존 active=1 -> 0 업데이트가 DB에 반영되기 전에
            // 새 hold(active=1) insert가 먼저 flush되면 유니크 제약(ux_reservation_active) 위반 가능
            reservationRepository.flush();
        }

        try {
            Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
            return reservationRepository.save(hold);
        } catch (DataIntegrityViolationException e) {
            // 동시성 레이스로 이미 다른 트랜잭션이 active=1을 잡은 케이스
            throw new BusinessException(ErrorCode.ALREADY_HELD);
        }
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

    /**
     * 사용자가 직접 선점 해제(취소)하는 케이스.
     * - 이미 CONFIRMED면 취소 불가
     * - HELD면 CANCELLED + active=0
     */
    @Transactional
    public void cancelHold(Long userId, Long scheduleId, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        var r = reservationRepository.findActiveForUpdate(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOLD_NOT_FOUND));

        if (!r.getUserId().equals(userId)) throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        if (r.getStatus() == ReservationStatus.CONFIRMED) throw new BusinessException(ErrorCode.ALREADY_RESERVED);

        r.cancel(now);
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
