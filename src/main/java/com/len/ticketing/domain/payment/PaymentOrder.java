package com.len.ticketing.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "payment_order",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_order_no",
                        columnNames = {"order_no"}
                )
        }
)
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_no", nullable = false, length = 20)
    private String seatNo;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "order_no", nullable = false, length = 50)
    private String orderNo;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private PaymentOrder(Long userId, Long scheduleId, String seatNo, int amount, String orderNo) {
        this.userId = userId;
        this.scheduleId = scheduleId;
        this.seatNo = seatNo;
        this.amount = amount;
        this.orderNo = orderNo;
        this.status = PaymentStatus.READY;
        this.createdAt = LocalDateTime.now();
    }

    public static PaymentOrder create(Long userId, Long scheduleId, String seatNo, int amount, String orderNo) {
        return new PaymentOrder(userId, scheduleId, seatNo, amount, orderNo);
    }

    public void markPaid() {
        this.status = PaymentStatus.PAID;
        this.failReason = null; // 필드 있으면
    }

    public void markCancelled(String reason) {
        this.status = PaymentStatus.CANCELLED;
        this.failReason = reason; // 필드 있으면
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
