package com.len.ticketing.application.reservation;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.reservation.ConfirmedSeatGuardStore;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final SeatJpaRepository seatRepository;
    private final ReservationJpaRepository reservationRepository;
    private final ConfirmedSeatGuardStore confirmedSeatGuardStore; // DB 최종 방어막

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

        if (userId == null || scheduleId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        if (!seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn)) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

        try {
            Reservation hold = Reservation.newHold(userId, scheduleId, sn, now, HOLD_TTL);

            // 핵심: saveAndFlush 금지 (flush 예외 후 세션 꼬임/AssertionFailure 방지)
            // 커밋 시점에 flush 되도록 save()만 사용
            return reservationRepository.save(hold);

        } catch (DataIntegrityViolationException e) {
            // A안: 유니크(좌석당 active=1 1건) 충돌이면 무조건 409로 종료
            if (isUkReservationActiveSeat(e)) {
                throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED); // HttpStatus.CONFLICT
            }
            throw e;
        }
    }

    private boolean isUkReservationActiveSeat(Throwable e) {
        // 1) Hibernate ConstraintViolationException의 constraintName으로 식별
        Throwable t = e;
        while (t != null) {
            if (t instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.equalsIgnoreCase("uk_reservation_active_seat")) {
                    return true;
                }
            }
            t = t.getCause();
        }

        // 2) fallback: 메시지에 인덱스/제약 이름이 포함되는 경우
        String msg = e.getMessage();
        return msg != null && msg.contains("uk_reservation_active_seat");
    }

    // ---------- CONFIRM ----------
    @Transactional
    public void confirm(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        if (userId == null || scheduleId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        // 0) 현재 활성 예약(HELD)을 먼저 조회해서 reservationId 확보
        //    (이미 CONFIRMED면 멱등 성공 처리)
        var cur = reservationRepository.findActiveLite(scheduleId, sn);
        if (cur == null) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);

        if ("CONFIRMED".equals(cur.getStatus())) {
            // 이미 확정됨 -> 멱등 성공
            return;
        }

        if (!"HELD".equals(cur.getStatus())) {
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        }

        // 소유자/만료 검증 (기존 정책 유지)
        if (cur.getUserId() != null && !cur.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND); // 정책상 소유자 아니면 not found
        }

        if (cur.getExpiresAt() == null || !cur.getExpiresAt().isAfter(now)) {
            reservationRepository.expireIfExpired(scheduleId, sn, now);
            throw new BusinessException(ErrorCode.HOLD_EXPIRED);
        }

        Long reservationId = cur.getId();
        if (reservationId == null) {
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        }

        // 1) DB 최종 방어막을 먼저 시도 (좌석당 1명만 통과)
        //    중복확정/재처리 -> PK 충돌 -> 멱등 성공 처리
        try {
            confirmedSeatGuardStore.acquire(scheduleId, sn, reservationId);
        } catch (DataIntegrityViolationException e) {
            // PK(schedule_id, seat_no) 충돌 = 이미 확정됨(또는 재처리)
            return; // 멱등 성공 처리
        }

        // 2) 원자적 confirm (내 HELD만 CONFIRMED로)
        int updated = reservationRepository.confirmHold(userId, scheduleId, sn, now);
        if (updated == 0) {
            // 여기까지 왔는데 updated=0이면: 직전에 상태가 바뀐 케이스
            // 정책상 not found로 처리
            throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        }
    }

    // ---------- CANCEL (사용자 액션 전용) ----------
    @Transactional
    public void cancelHold(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        if (userId == null || scheduleId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

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

        if (userId == null || scheduleId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        int updated = reservationRepository.cancelHold(userId, scheduleId, sn, now);
        return updated > 0;
    }

    // ---------- 읽기 ----------
    @Transactional(readOnly = true)
    public void assertValidHoldOwner(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        if (userId == null || scheduleId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        var cur = reservationRepository.findActiveLite(scheduleId, sn);
        if (cur == null) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (cur.getUserId() == null || !cur.getUserId().equals(userId)) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (!"HELD".equals(cur.getStatus())) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (cur.getExpiresAt() == null || !cur.getExpiresAt().isAfter(now)) throw new BusinessException(ErrorCode.HOLD_EXPIRED);
    }

    // ---------- HasValidHold ----------
    @Transactional(readOnly = true)
    public boolean hasValidHold(Long userId, Long scheduleId, String seatNo, LocalDateTime now) {
        if (userId == null || scheduleId == null || seatNo == null || seatNo.isBlank() || now == null) {
            return false;
        }
        String sn = seatNo.trim().toUpperCase();
        return reservationRepository.countValidHold(userId, scheduleId, sn, now) > 0;
    }
}