package com.len.ticketing.application.payment;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.concert.Seat;
import com.len.ticketing.domain.payment.PaymentOrder;
import com.len.ticketing.domain.payment.PaymentStatus;
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

    @Transactional
    public PaymentReadyResult ready(Long userId, Long scheduleId, String seatNo) {
        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        Seat seat = seatRepository.findByScheduleIdAndSeatNo(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        var r = reservationRepository.findActiveForUpdate(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_SEAT_OWNER));

        if (!r.getUserId().equals(userId)) throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        if (r.getStatus() != ReservationStatus.HELD) throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        if (r.getExpiresAt() != null && r.getExpiresAt().isBefore(now)) {
            r.expire(now);
            throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        }

        int amount = seat.getPrice();
        String orderNo = "PO-" + UUID.randomUUID();

        paymentOrderRepository.save(PaymentOrder.create(userId, scheduleId, sn, amount, orderNo));
        return new PaymentReadyResult(orderNo, amount, "결제 준비가 완료되었습니다.");
    }

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

        order.markPaid();

        // ✅ 새 Reservation INSERT 하지 말고, 기존 HOLD를 CONFIRMED로 변경
        reservationService.confirm(order.getUserId(), order.getScheduleId(), order.getSeatNo());

        return new PaymentResult(true, "예매가 확정되었습니다.");
    }

    public record PaymentReadyResult(String orderNo, int amount, String message) {}
    public record PaymentResult(boolean success, String message) {}
}
