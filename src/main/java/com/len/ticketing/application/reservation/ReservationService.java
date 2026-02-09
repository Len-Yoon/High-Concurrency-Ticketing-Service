package com.len.ticketing.application.reservation;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.reservation.ConfirmedSeatGuardStore;
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
    private final ConfirmedSeatGuardStore confirmedSeatGuardStore; // ✅ 추가

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

        // 1) 원자적 confirm 시도
        int updated = reservationRepository.confirmHold(userId, scheduleId, sn, now);
        if (updated > 0) {
            // 2) ✅ DB 최종 방어막: confirmed_seat_guard 삽입
            //    중복 키면 이미 누군가 확정한 좌석으로 보고 롤백
            var cur = reservationRepository.findActiveLite(scheduleId, sn);
            if (cur == null || cur.getId() == null) {
                // 거의 없지만 방어적으로 처리
                throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
            }

            try {
                confirmedSeatGuardStore.acquire(scheduleId, sn, cur.getId());
            } catch (DataIntegrityViolationException e) {
                // UNIQUE(schedule_id, seat_no) 충돌
                throw new BusinessException(ErrorCode.ALREADY_RESERVED);
            }
            return;
        }

        // 3) 실패 시 상태 조회 후 정확한 에러 매핑
        var cur = reservationRepository.findActiveLite(scheduleId, sn);
        if (cur == null) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);

        if ("CONFIRMED".equals(cur.getStatus())) {
            throw new BusinessException(ErrorCode.ALREADY_RESERVED);
        }

        if ("HELD".equals(cur.getStatus())) {
            if (cur.getUserId() != null && !cur.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.HOLD_NOT_FOUND); // 정책상 소유자 아니면 not found
            }

            if (cur.getExpiresAt() != null && !cur.getExpiresAt().isAfter(now)) { // expiresAt <= now
                reservationRepository.expireIfExpired(scheduleId, sn, now);
                throw new BusinessException(ErrorCode.HOLD_EXPIRED);
            }
        }

        throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
    }

    // ---------- CANCEL (사용자 액션 전용) ----------
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

    // ---------- HasValidHold --------
    @Transactional(readOnly = true)
    public boolean hasValidHold(Long userId, Long scheduleId, String seatNo, LocalDateTime now) {
        String sn = seatNo == null ? null : seatNo.trim().toUpperCase();
        return reservationRepository.countValidHold(userId, scheduleId, sn, now) > 0;
    }
}
