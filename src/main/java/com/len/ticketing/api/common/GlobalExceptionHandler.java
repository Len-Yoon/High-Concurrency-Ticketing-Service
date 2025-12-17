package com.len.ticketing.api.common;

import com.len.ticketing.application.reservation.SeatAlreadyReservedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ===== 이미 예약된 좌석 =====
    @ExceptionHandler(SeatAlreadyReservedException.class)
    public ResponseEntity<ErrorResponse> handleSeatAlreadyReserved(
            SeatAlreadyReservedException e,
            HttpServletRequest request
    ) {
        log.warn("[SEAT_ALREADY_RESERVED] {} {} - {}",
                request.getMethod(), request.getRequestURI(), e.getMessage());

        HttpStatus status = HttpStatus.CONFLICT; // 409

        ErrorResponse body = ErrorResponse.of(
                status.value(),
                "SEAT_ALREADY_RESERVED",
                e.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(body);
    }

    // ===== DB 관련 예외 =====
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(
            DataAccessException e,
            HttpServletRequest request
    ) {
        log.error("[DB_ERROR] {} {}",
                request.getMethod(), request.getRequestURI(), e);

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;

        ErrorResponse body = ErrorResponse.of(
                status.value(),
                "DB_ERROR",
                "데이터베이스 오류가 발생했습니다.",
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(body);
    }

    // ===== 그 외 모든 예외 =====
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception e,
            HttpServletRequest request
    ) {
        log.error("[INTERNAL_ERROR] {} {}",
                request.getMethod(), request.getRequestURI(), e);

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;

        ErrorResponse body = ErrorResponse.of(
                status.value(),
                "INTERNAL_ERROR",
                "알 수 없는 오류가 발생했습니다.",
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(body);
    }
}
