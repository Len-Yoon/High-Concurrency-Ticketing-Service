package com.len.ticketing.application.reservation;

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

    private static final Duration HOLD_TTL = Duration.ofMinutes(3);

    private final ReservationJpaRepository reservationRepository;
    private final SeatJpaRepository seatRepository;

    @Transactional
    public Reservation hold(Long userId, Long scheduleId, String seatNoRaw) {
        String seatNo = normalizeSeatNo(seatNoRaw);
        LocalDateTime now = LocalDateTime.now();

        // 좌석 존재 체크
        if (!seatRepository.existsByScheduleIdAndSeatNo(scheduleId, seatNo)) {
            throw new IllegalArgumentException("없는 좌석");
        }

        // 기존 점유 row(락)
        var activeOpt = reservationRepository.findActiveForUpdate(scheduleId, seatNo);

        if (activeOpt.isPresent()) {
            Reservation r = activeOpt.get();

            // 확정 예매는 무조건 막기
            if (r.getStatus() == ReservationStatus.CONFIRMED) {
                throw new IllegalStateException("이미 예매된 좌석");
            }

            // 홀드가 아직 유효하면 막기
            if (r.getStatus() == ReservationStatus.HELD && !r.isExpired(now)) {
                throw new IllegalStateException("이미 홀드된 좌석");
            }

            // 만료된 HELD라면 해제(Expire) 처리
            r.expire(now);
            // 여기서 active=null로 풀림
        }

        // 새 HOLD 생성 (동시성 최종 방어: 유니크 인덱스)
        try {
            return reservationRepository.save(Reservation.newHold(userId, scheduleId, seatNo, now, HOLD_TTL));
        } catch (DataIntegrityViolationException e) {
            // 누가 먼저 잡았음
            throw new IllegalStateException("이미 홀드/예매된 좌석");
        }
    }

    @Transactional
    public void confirm(Long userId, Long scheduleId, String seatNoRaw) {
        String seatNo = normalizeSeatNo(seatNoRaw);
        LocalDateTime now = LocalDateTime.now();

        Reservation r = reservationRepository.findActiveForUpdate(scheduleId, seatNo)
                .orElseThrow(() -> new IllegalStateException("홀드 없음"));

        if (!r.getUserId().equals(userId)) {
            throw new IllegalStateException("본인 홀드 아님");
        }
        if (r.getStatus() == ReservationStatus.HELD && r.isExpired(now)) {
            r.expire(now);
            throw new IllegalStateException("홀드 만료");
        }

        r.confirm(now);
    }

    @Transactional
    public void cancel(Long userId, Long scheduleId, String seatNoRaw) {
        String seatNo = normalizeSeatNo(seatNoRaw);
        LocalDateTime now = LocalDateTime.now();

        Reservation r = reservationRepository.findActiveForUpdate(scheduleId, seatNo)
                .orElseThrow(() -> new IllegalStateException("홀드 없음"));

        if (!r.getUserId().equals(userId)) {
            throw new IllegalStateException("본인 홀드 아님");
        }

        r.cancel(now); // active=null로 해제
    }

    private String normalizeSeatNo(String raw) {
        return raw == null ? null : raw.trim().toUpperCase();
    }
}
