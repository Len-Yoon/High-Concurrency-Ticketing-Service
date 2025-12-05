package com.len.ticketing.application.payment;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.concert.Seat;
import com.len.ticketing.domain.payment.PaymentOrder;
import com.len.ticketing.domain.payment.PaymentStatus;
import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.domain.ticket.SeatLockStore;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import com.len.ticketing.infra.payment.PaymentOrderJpaRepository;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@RequiredArgsConstructor
@Service
public class PaymentService {

    private final SeatJpaRepository seatRepository;
    private final SeatLockStore seatLockStore;
    private final PaymentOrderJpaRepository paymentOrderRepository;
    private final ReservationJpaRepository reservationRepository;

    /**
     * 결제 준비: 좌석/락 확인 후 PaymentOrder 생성
     */
    @Transactional
    public PaymentReadyResult ready(Long userId, Long scheduleId, String seatNo) {
        // 1. 좌석 존재 여부 확인
        Seat seat = seatRepository.findByScheduleIdAndSeatNo(scheduleId, seatNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));

        // 2. 이 사용자가 진짜 좌석을 선점했는지 확인
        Long owner = seatLockStore.getLockOwner(scheduleId, seatNo);
        if (owner == null || !owner.equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_SEAT_OWNER);
        }

        int amount = seat.getPrice();

        // 3. 내부 결제 주문 번호 생성 (UUID)
        String orderNo = "PO-" + UUID.randomUUID();

        // 4. PaymentOrder 생성 및 저장
        PaymentOrder order = PaymentOrder.create(userId, scheduleId, seatNo, amount, orderNo);
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

        try {
            // 1) 결제 상태 변경
            order.markPaid();

            // 2) 예매 생성 (중복 예매는 DB Unique 제약으로 막음)
            Reservation reservation = Reservation.create(
                    order.getUserId(),
                    order.getScheduleId(),
                    order.getSeatNo()
            );
            reservationRepository.save(reservation);

            return new PaymentResult(true, "예매가 확정되었습니다.");
        } catch (DataIntegrityViolationException e) {
            // 누군가 이미 같은 좌석을 예약한 경우
            order.markCancelled("이미 예매된 좌석입니다.");
            return new PaymentResult(false, "이미 예매된 좌석입니다.");
        }
    }

    // === 결과 DTO (내부용) ===

    public record PaymentReadyResult(
            String orderNo,
            int amount,
            String message
    ) {}

    public record PaymentResult(
            boolean success,
            String message
    ) {}
}
