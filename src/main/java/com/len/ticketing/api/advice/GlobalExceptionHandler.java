package com.len.ticketing.api.advice;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 우리가 정의한 비즈니스 예외
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(
            BusinessException ex,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ex.getErrorCode();
        ErrorResponse body = ErrorResponse.of(errorCode, request.getRequestURI());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    // JPA Unique 제약 등 무결성 오류 (중복 예매 등)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.ALREADY_RESERVED;
        ErrorResponse body = ErrorResponse.of(errorCode, request.getRequestURI());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    // 그 외 예상 못한 예외들
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleEtc(
            Exception ex,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        ErrorResponse body = ErrorResponse.of(errorCode, request.getRequestURI());
        ex.printStackTrace(); // 콘솔에는 전체 스택 찍어두기
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }
}
