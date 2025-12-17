package com.len.ticketing.api.advice;

import com.len.ticketing.application.reservation.SeatAlreadyReservedException;
import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1) 좌석 중복 예약 (도메인 예외)
    @ExceptionHandler(SeatAlreadyReservedException.class)
    public ResponseEntity<ErrorResponse> handleSeatAlreadyReserved(
            SeatAlreadyReservedException ex,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.ALREADY_RESERVED;
        ErrorResponse body = ErrorResponse.of(errorCode, request.getRequestURI());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    // 2) 우리가 정의한 비즈니스 예외
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(
            BusinessException ex,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ex.getErrorCode();
        ErrorResponse body = ErrorResponse.of(errorCode, request.getRequestURI());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    // 3) JPA Unique 제약 등 무결성 오류
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.ALREADY_RESERVED;
        ErrorResponse body = ErrorResponse.of(errorCode, request.getRequestURI());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    // 4) 그 외 예상 못한 예외들 (디버그 정보 포함)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleEtc(
            Exception ex,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;

        // 예외 타입 + 메시지를 message에 넣어서 바로 확인할 수 있게
        String debugMessage = errorCode.getMessage()
                + " [" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + "]";

        ErrorResponse body = ErrorResponse.of(errorCode, request.getRequestURI(), debugMessage);

        ex.printStackTrace(); // 콘솔에 원인 찍기

        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }
}
