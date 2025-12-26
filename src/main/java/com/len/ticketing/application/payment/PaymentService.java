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
import com.len.ticketing.application.reservation.ReservationService;
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
    private final ReservationService reservationService;

    @Transactional
    public PaymentReadyResult ready(Long userId, Long scheduleId, String seatNo) {
        if (userId == null || scheduleId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        // 1) 좌석 존재 확인
        Seat seat = seatRepository.findBySchedule_IdAndSeatNo(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        // 2) DB 홀드(HELD, active=1, 만료 전) + 소유자 확인
        Reservation r = reservationRepository.findActiveForUpdate(scheduleId, sn)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOLD_NOT_FOUND));

        if (!r.getUserId().equals(userId)) throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        if (r.getStatus() != ReservationStatus.HELD) throw new BusinessException(ErrorCode.HOLD_NOT_FOUND);
        if (r.getExpiresAt() != null && !r.getExpiresAt().isAfter(now)) throw new BusinessException(ErrorCode.HOLD_EXPIRED);

        // 3) 결제 주문 생성
        String orderNo = "PO-" + UUID.randomUUID();
        PaymentOrder order = PaymentOrder.create(userId, scheduleId, sn, seat.getPrice(), orderNo);
        paymentOrderRepository.save(order);

        return new PaymentReadyResult(orderNo, seat.getPrice(), "결제 준비 완료");
    }

    @Transactional
    public PaymentResult mockSuccess(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        PaymentOrder order = paymentOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        if (order.getStatus() == PaymentStatus.PAID) {
            return new PaymentResult(true, "이미 결제 완료");
        }

        try {
            // 1) hold -> confirmed
            reservationService.confirm(order.getUserId(), order.getScheduleId(), order.getSeatNo());

            // 2) 결제 완료 처리
            order.markPaid();
            order.setUpdatedAt(LocalDateTime.now());

            return new PaymentResult(true, "예매 확정 완료");
        } catch (BusinessException e) {
            order.markCancelled(e.getMessage());
            order.setUpdatedAt(LocalDateTime.now());
            return new PaymentResult(false, e.getMessage());
        } catch (RuntimeException e) {
            order.markCancelled(e.getMessage());
            order.setUpdatedAt(LocalDateTime.now());
            return new PaymentResult(false, "예매 확정 실패: " + e.getMessage());
        }
    }

    public record PaymentReadyResult(String orderNo, int amount, String message) {}
    public record PaymentResult(boolean success, String message) {}
}
