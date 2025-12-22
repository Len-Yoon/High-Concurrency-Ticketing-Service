package com.len.ticketing.application.payment;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.concert.Seat;
import com.len.ticketing.domain.payment.PaymentOrder;
import com.len.ticketing.domain.payment.PaymentStatus;
import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.domain.reservation.ReservationStatus;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.payment.PaymentOrderJpaRepository;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class PaymentService {

    private final SeatJpaRepository seatRepository;
    private final PaymentOrderJpaRepository paymentOrderRepository;
    private final ReservationJpaRepository reservationRepository;
    private final com.len.ticketing.application.reservation.ReservationService reservationService;

    /**
     * 결제 준비: 좌석 존재 + (DB hold 선점자) 확인 후 PaymentOrder 생성
     */
    @Transactional
    public PaymentReadyResult ready(Long userId, Long scheduleId, String seatNo) {
        String sn = normalizeSeatNo(seatNo);
        LocalDateTime now = LocalDateTime.now();

        // 1) 좌석 존재 여부 확인
        Seat seat = seatRepository.findByScheduleIdAndSeatNo(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        // 2) 이 사용자가 진짜 좌석을 선점(HELD/active=1)했는지 확인
        Reservation r = reservationRepository.findActiveForUpdate(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SEAT_OWNER));

        // 선점자 불일치
        if (!r.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        }

        // 상태가 HELD가 아니면 결제 준비 불가(이미 확정 등)
        if (r.getStatus() != ReservationStatus.HELD) {
            throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        }

        // 만료면 선점 아님 처리(옵션: 여기서 expire로 정리까지)
        if (r.getExpiresAt() != null && r.getExpiresAt().isBefore(now)) {
            r.expire(now);
            throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        }

        int amount = seat.getPrice();

        // 3) 내부 결제 주문 번호 생성
        String orderNo = "PO-" + UUID.randomUUID();

        // 4) PaymentOrder 생성 및 저장
        PaymentOrder order = PaymentOrder.create(userId, scheduleId, sn, amount, orderNo);
        paymentOrderRepository.save(order);

        return new PaymentReadyResult(orderNo, amount, "결제 준비가 완료되었습니다.");
    }

    /**
     * (모의) 결제 성공 콜백 → 예매 확정
     */
    @Transactional
    public PaymentResult mockSuccess(String orderNo) {
        PaymentOrder order = paymentOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("주문이 존재하지 않습니다."));

        if (order.getStatus() == PaymentStatus.PAID) {
            return new PaymentResult(true, "이미 결제가 완료된 주문입니다.");
        }
        if (order.getStatus() == PaymentStatus.CANCELLED || order.getStatus() == PaymentStatus.FAILED) {
            return new PaymentResult(false, "이미 취소되었거나 실패한 주문입니다.");
        }

        // 1) 결제 상태 변경
        order.markPaid();

        // 2) 예매 확정: 새 reservation INSERT 하지 말고(꼬임),
        //    기존 HOLD를 CONFIRMED로 바꿔라
        try {
            reservationService.confirm(
                    order.getUserId(),
                    order.getScheduleId(),
                    normalizeSeatNo(order.getSeatNo())
            );
            return new PaymentResult(true, "예매가 확정되었습니다.");
        } catch (BusinessException e) {
            // HOLD 없거나 만료/선점자 불일치 등
            order.markCancelled("좌석 선점이 유효하지 않습니다.");
            return new PaymentResult(false, "좌석 선점이 유효하지 않습니다.");
        }
    }

    private String normalizeSeatNo(String raw) {
        return raw == null ? null : raw.trim().toUpperCase();
    }

    // === 결과 DTO (내부용) ===
    public record PaymentReadyResult(String orderNo, int amount, String message) {}
    public record PaymentResult(boolean success, String message) {}
}
