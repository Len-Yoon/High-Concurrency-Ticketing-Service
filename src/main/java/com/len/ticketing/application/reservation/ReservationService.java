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
        if (!seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn)) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

        // ✅ 1) 일단 INSERT 먼저 (유니크가 동시성 보장)
        try {
            Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
            return reservationRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException e) {
            // ✅ 2) 충돌이면 현재 active row를 조회해서 판단
            var cur = reservationRepository.findActiveLite(scheduleId, sn);

            if (cur == null) {
                // 아주 짧은 타이밍에 상태 바뀐 케이스 -> 한번만 재시도
                Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
                return reservationRepository.saveAndFlush(hold);
            }

            // CONFIRMED면 끝
            if ("CONFIRMED".equals(cur.getStatus())) {
                throw new BusinessException(ErrorCode.ALREADY_RESERVED);
            }

            // HELD인데 만료면 -> 내가 만료처리하고 1회 재시도
            if ("HELD".equals(cur.getStatus()) && cur.getExpiresAt() != null && cur.getExpiresAt().isBefore(now)) {
                int expired = reservationRepository.expireIfExpired(scheduleId, sn, now);
                if (expired > 0) {
                    Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
                    return reservationRepository.saveAndFlush(hold);
                }
            }

            // ✅ 같은 유저가 이미 잡은 거면 멱등 성공 처리(선택)
            if (cur.getUserId() != null && cur.getUserId().equals(userId)) {
                // 이미 내가 잡은 상태면 그냥 성공으로 봐도 됨
                // 필요하면 existing 엔티티를 다시 조회해서 반환하도록 바꿔도 됨
                return reservationRepository.findById(cur.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ALREADY_HELD));
            }

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
