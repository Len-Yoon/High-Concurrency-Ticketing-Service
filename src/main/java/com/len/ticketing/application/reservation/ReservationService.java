package com.len.ticketing.application.reservation;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.reservation.Reservation;
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

        if (!seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn)) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

        // ✅ 1) INSERT 먼저 (유니크 인덱스가 동시성의 단일 진실)
        try {
            Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
            return reservationRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException e) {

            // ✅ 2) 충돌이면 현재 active 상태를 보고 분기
            var cur = reservationRepository.findActiveLite(scheduleId, sn);

            if (cur == null) {
                // 아주 짧은 타이밍 레이스 -> 1회만 재시도
                Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
                return reservationRepository.saveAndFlush(hold);
            }

            if ("CONFIRMED".equals(cur.getStatus())) {
                throw new BusinessException(ErrorCode.ALREADY_RESERVED);
            }

            // 만료 HELD면 내가 정리하고 1회 재시도
            if ("HELD".equals(cur.getStatus())
                    && cur.getExpiresAt() != null
                    && cur.getExpiresAt().isBefore(now)) {

                int fixed = reservationRepository.expireIfExpired(scheduleId, sn, now);
                if (fixed > 0) {
                    Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
                    return reservationRepository.saveAndFlush(hold);
                }
            }

            // 같은 유저가 이미 잡은 거면 멱등 처리(선택)
            if (cur.getUserId() != null && cur.getUserId().equals(userId)) {
                return reservationRepository.findById(cur.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ALREADY_HELD));
            }

            throw new BusinessException(ErrorCode.ALREADY_HELD);
        }
    }

    /**
     * ✅ 결제 완료(확정)
     * - FOR UPDATE로 락 잡고 엔티티 변경보다
     * - "조건부 UPDATE 1방"이 부하에서 더 안전(락 범위/시간 최소화)
     */
    @Transactional
    public void confirm(Long userId, Long scheduleId, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        // 먼저 만료면 내리고(HOLD_EXPIRED)
        int expired = reservationRepository.expireIfExpired(scheduleId, sn, now);
        if (expired > 0) {
            throw new BusinessException(ErrorCode.HOLD_EXPIRED);
        }

        // ✅ HELD + active=1 + owner=userId 인 경우에만 CONFIRMED로 전환
        int updated = reservationRepository.confirmHold(userId, scheduleId, sn, now);
        if (updated == 0) {
            // 원인 분기(메시지 정확도용). 성능상 부담 없게 lite로만 확인
            var cur = reservationRepository.findActiveLite(scheduleId, sn);
            if (cur == null) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
            if ("CONFIRMED".equals(cur.getStatus())) throw new BusinessException(ErrorCode.ALREADY_RESERVED);
            if (cur.getUserId() != null && !cur.getUserId().equals(userId)) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        }
    }

    /**
     * 사용자가 직접 선점 해제(취소)하는 케이스.
     * - UPDATE 1방(INSERT 금지)
     */
    @Transactional
    public void cancelHold(Long userId, Long scheduleId, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        int updated = reservationRepository.cancelHold(userId, scheduleId, sn, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        }
    }

    /**
     * ✅ 결제 API/다른 API에서 "내가 유효 홀더인지" 확인
     * - 락 걸지 말고 Lite 조회로만 판단(읽기)
     */
    @Transactional(readOnly = true)
    public void assertValidHoldOwner(Long userId, Long scheduleId, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        var cur = reservationRepository.findActiveLite(scheduleId, sn);
        if (cur == null) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (cur.getUserId() == null || !cur.getUserId().equals(userId)) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (!"HELD".equals(cur.getStatus())) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (cur.getExpiresAt() == null || !cur.getExpiresAt().isAfter(now)) throw new BusinessException(ErrorCode.HOLD_EXPIRED);
    }

    @Transactional
    public boolean cancelHoldIfExists(Long userId, Long scheduleId, String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        int updated = reservationRepository.cancelHold(userId, scheduleId, sn, now);
        return updated > 0;
    }
}
