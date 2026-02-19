package com.len.ticketing.application.payment;

import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.concert.Seat;
import com.len.ticketing.domain.payment.PaymentOrder;
import com.len.ticketing.domain.payment.PaymentStatus;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.payment.PaymentOrderJpaRepository;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
        //    ❗️기존 findActiveForUpdate(scheduleId, seatNo)는
        //    - CONFIRMED(active=1) + HELD(active=1) 같이 2개 이상이면 500(IncorrectResultSize) 터짐
        //    - 타 유저의 active row를 잡아 NOT_SEAT_OWNER로 떨어지기도 함
        //    => "해당 사용자"의 유효 HOLD 1건만(native + LIMIT 1)으로 고정
        var r = reservationRepository.findLatestValidHoldForUpdate(userId, scheduleId, sn, now)
                .orElseThrow(() -> new BusinessException(ErrorCode.HOLD_NOT_FOUND));

        // (강추) 혹시 남아있는 중복 HELD를 여기서 정리(ready가 500으로 죽는 재발 방지)
        reservationRepository.expireOtherActiveHolds(userId, scheduleId, sn, r.getId(), now);

        // 3) 결제 주문 생성 (amount는 DB seat.price 기준으로 확정)
        String orderNo = "PO-" + UUID.randomUUID();
        PaymentOrder order = PaymentOrder.create(userId, scheduleId, sn, seat.getPrice(), orderNo);
        paymentOrderRepository.save(order);

        return new PaymentReadyResult(orderNo, seat.getPrice(), "결제 준비 완료");
    }

    /**
     * 핵심:
     * - 기존 UnexpectedRollbackException 방지를 위해 바깥 트랜잭션을 사용하지 않음
     * - confirm()에서 실패해도 catch 후 취소 상태 저장 가능
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
            // 1) hold -> confirmed (reservationService.confirm 내부 트랜잭션 사용)
            reservationService.confirm(order.getUserId(), order.getScheduleId(), order.getSeatNo());

            // 2) 결제 완료 처리
            order.markPaid();
            order.setUpdatedAt(LocalDateTime.now());
            paymentOrderRepository.saveAndFlush(order);

            return new PaymentResult(true, "예매 확정 완료");
        } catch (BusinessException e) {
            markCancelledSafely(order, e.getMessage());
            return new PaymentResult(false, e.getMessage());
        } catch (RuntimeException e) {
            markCancelledSafely(order, e.getMessage());
            return new PaymentResult(false, "예매 확정 실패: " + e.getMessage());
        }
    }

    private void markCancelledSafely(PaymentOrder order, String reason) {
        try {
            String failReason = (reason == null || reason.isBlank()) ? "예매 확정 실패" : reason;
            order.markCancelled(failReason);
            order.setUpdatedAt(LocalDateTime.now());
            paymentOrderRepository.saveAndFlush(order);
        } catch (Exception ignored) {
            // 취소 저장 실패는 원래 예외를 덮지 않기 위해 무시
        }
    }

    public record PaymentReadyResult(String orderNo, int amount, String message) {
    }

    public record PaymentResult(boolean success, String message) {
    }
}
