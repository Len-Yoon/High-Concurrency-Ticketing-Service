package com.len.ticketing.api.advice;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest req) {
        ErrorCode ec = e.getErrorCode();
        return ResponseEntity
                .status(ec.getHttpStatus())
                .body(new ErrorResponse(
                        LocalDateTime.now(),
                        ec.getHttpStatus().value(),
                        ec.getCode(),
                        e.getMessage(),
                        req.getRequestURI()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e, HttpServletRequest req) {
        ErrorCode ec = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity
                .status(ec.getHttpStatus())
                .body(new ErrorResponse(
                        LocalDateTime.now(),
                        ec.getHttpStatus().value(),
                        ec.getCode(),
                        ec.getMessage() + " [" + e.getClass().getSimpleName() + ": " + e.getMessage() + "]",
                        req.getRequestURI()
                ));
    }

    public record ErrorResponse(
            LocalDateTime timestamp,
            int status,
            String code,
            String message,
            String path
    ) {}
}
