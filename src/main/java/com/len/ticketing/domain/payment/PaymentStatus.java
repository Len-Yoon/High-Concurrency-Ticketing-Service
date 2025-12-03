package com.len.ticketing.domain.payment;

public enum PaymentStatus {
    READY,      // 결제 준비 완료 (결제 대기)
    PAID,       // 결제 성공
    CANCELLED,  // 취소/실패
    FAILED      // 시스템 에러 등
}
