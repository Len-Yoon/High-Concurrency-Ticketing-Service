package com.len.ticketing.api.advice;

import com.len.ticketing.common.exception.ErrorCode;

import java.time.LocalDateTime;

public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String code,
        String message,
        String path
) {
    public static ErrorResponse of(ErrorCode errorCode, String path) {
        return new ErrorResponse(
                LocalDateTime.now(),
                errorCode.getHttpStatus().value(),
                errorCode.getCode(),
                errorCode.getMessage(),
                path
        );
    }

    public static ErrorResponse of(int status, String message, String path) {
        return new ErrorResponse(
                LocalDateTime.now(),
                status,
                null,
                message,
                path
        );
    }
}
