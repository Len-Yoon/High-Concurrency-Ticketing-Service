package com.len.ticketing.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ===== 공통 =====
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "알 수 없는 오류가 발생했습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다."),

    // ===== 좌석/락/예매 =====
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND", "좌석이 존재하지 않습니다."),
    NOT_SEAT_OWNER(HttpStatus.BAD_REQUEST, "NOT_SEAT_OWNER", "해당 사용자가 이 좌석을 선점하지 않았습니다."),
    SEAT_ALREADY_LOCKED(HttpStatus.CONFLICT, "SEAT_ALREADY_LOCKED", "이미 다른 사용자가 선점한 좌석입니다."),
    ALREADY_RESERVED(HttpStatus.CONFLICT, "ALREADY_RESERVED", "이미 예매된 좌석입니다."),
    ALREADY_HELD(HttpStatus.CONFLICT, "ALREADY_HELD", "이미 홀드된 좌석입니다."),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "홀드 정보가 없습니다."),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "HOLD_EXPIRED", "홀드가 만료되었습니다."),

    // ===== 대기열 =====
    QUEUE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "QUEUE_NOT_ALLOWED", "대기열 입장 가능 상태가 아닙니다."),

    // ===== 결제 =====
    PAYMENT_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "주문이 존재하지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    // 기존 코드에서 ec.getStatus()를 쓰고 있으니 호환용으로 유지
    public HttpStatus getStatus() {
        return httpStatus;
    }
}
