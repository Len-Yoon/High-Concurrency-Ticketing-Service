package com.len.ticketing.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "알 수 없는 오류가 발생했습니다."),

    // 좌석 / 예매 관련
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND", "좌석이 존재하지 않습니다."),
    NOT_SEAT_OWNER(HttpStatus.BAD_REQUEST, "NOT_SEAT_OWNER", "해당 사용자가 이 좌석을 선점하지 않았습니다."),
    SEAT_ALREADY_LOCKED(HttpStatus.CONFLICT, "SEAT_ALREADY_LOCKED", "이미 다른 사용자가 선점한 좌석입니다."),
    ALREADY_RESERVED(HttpStatus.CONFLICT, "ALREADY_RESERVED", "이미 예매된 좌석입니다."),

    // 대기열 관련
    QUEUE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "QUEUE_NOT_ALLOWED", "대기열 입장 가능 상태가 아닙니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
