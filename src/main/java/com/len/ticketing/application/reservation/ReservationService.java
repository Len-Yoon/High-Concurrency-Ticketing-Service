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

    private static final Duration HOLD_TTL = Duration.ofMinutes(5);

    // ---------- 공통 유틸 ----------
    private String normalizeSeatNo(String seatNo) {
        if (seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return seatNo.trim().toUpperCase();
    }

    // ---------- HOLD ----------
    @Transactional
    public Reservation hold(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        if (!seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn)) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

        try {
            Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
            return reservationRepository.saveAndFlush(hold);

        } catch (DataIntegrityViolationException e) {
            var cur = reservationRepository.findActiveLite(scheduleId, sn);

            if (cur == null) {
                Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
                return reservationRepository.saveAndFlush(hold);
            }

            if ("CONFIRMED".equals(cur.getStatus())) {
                throw new BusinessException(ErrorCode.ALREADY_RESERVED);
            }

            if ("HELD".equals(cur.getStatus())
                    && cur.getExpiresAt() != null
                    && cur.getExpiresAt().isBefore(now)) {

                int fixed = reservationRepository.expireIfExpired(scheduleId, sn, now);
                if (fixed > 0) {
                    Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);
                    return reservationRepository.saveAndFlush(hold);
                }
            }

            if (cur.getUserId() != null && cur.getUserId().equals(userId)) {
                return reservationRepository.findById(cur.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ALREADY_HELD));
            }

            throw new BusinessException(ErrorCode.ALREADY_HELD);
        }
    }

    // ---------- CONFIRM ----------
    @Transactional
    public void confirm(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        // 1) 원자적 confirm 시도 (성공하면 끝)
        int updated = reservationRepository.confirmHold(userId, scheduleId, sn, now);
        if (updated > 0) return;

        // 2) 실패 시에만 현재 상태 조회해서 정확한 에러 매핑
        var cur = reservationRepository.findActiveLite(scheduleId, sn);
        if (cur == null) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);

        if ("CONFIRMED".equals(cur.getStatus())) {
            throw new BusinessException(ErrorCode.ALREADY_RESERVED);
        }

        // HELD인데 만료된 경우: 여기서 expire 처리 후 HOLD_EXPIRED
        if ("HELD".equals(cur.getStatus())) {
            if (cur.getUserId() != null && !cur.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.HOLD_NOT_FOUND); // 정책상 소유자 아니면 not found 처리
            }

            if (cur.getExpiresAt() != null && !cur.getExpiresAt().isAfter(now)) { // expiresAt <= now
                reservationRepository.expireIfExpired(scheduleId, sn, now);
                throw new BusinessException(ErrorCode.HOLD_EXPIRED);
            }
        }

        throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
    }

    // ---------- CANCEL (사용자 액션 전용) ----------
    /**
     * 사용자가 "취소" 버튼 눌렀을 때만 사용.
     * 내부 release/정리 로직에서는 절대 쓰지 말고 cancelHoldIfExists를 써라.
     */
    @Transactional
    public void cancelHold(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        int updated = reservationRepository.cancelHold(userId, scheduleId, sn, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        }
    }

    // ---------- RELEASE/정리용 멱등 ----------
    /**
     * release / 만료정리 / 레이스 정리 등 "멱등"이 필요한 내부 경로에서 사용.
     * 없으면 false 리턴 (예외 던지지 않음) -> UnexpectedRollback 방지.
     */
    @Transactional
    public boolean cancelHoldIfExists(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        int updated = reservationRepository.cancelHold(userId, scheduleId, sn, now);
        return updated > 0;
    }

    // ---------- 읽기 ----------
    @Transactional(readOnly = true)
    public void assertValidHoldOwner(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        var cur = reservationRepository.findActiveLite(scheduleId, sn);
        if (cur == null) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (cur.getUserId() == null || !cur.getUserId().equals(userId)) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (!"HELD".equals(cur.getStatus())) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (cur.getExpiresAt() == null || !cur.getExpiresAt().isAfter(now)) throw new BusinessException(ErrorCode.HOLD_EXPIRED);
    }
}
