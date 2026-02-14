package com.len.ticketing.api.advice;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest req) {
        ErrorCode ec = e.getErrorCode();
        return ResponseEntity
                .status(ec.getStatus())
                .body(new ErrorResponse(
                        LocalDateTime.now(),
                        ec.getStatus().value(),
                        ec.getCode(),
                        e.getMessage(),
                        req.getRequestURI()
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e,
            HttpServletRequest request
    ) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        String rootMsg = (root != null && root.getMessage() != null) ? root.getMessage() : e.getMessage();

        // 핵심: 실제 JSON 파싱 실패 원인 로그
        log.warn("[INVALID_JSON] path={}, cause={}", request.getRequestURI(), rootMsg);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", 400);
        body.put("code", "INVALID_JSON");
        body.put("message", "요청 JSON 형식이 올바르지 않습니다.");
        body.put("path", request.getRequestURI());
        body.put("detail", rootMsg); // 로컬 디버깅용(원하면 제거 가능)

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ) {
        String msg = e.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("요청 값이 올바르지 않습니다.");

        ErrorCode ec = ErrorCode.INVALID_REQUEST;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", ec.getStatus().value());
        body.put("code", ec.getCode());
        body.put("message", msg);
        body.put("path", request.getRequestURI());

        return ResponseEntity.status(ec.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e, HttpServletRequest req) {
        ErrorCode ec = ErrorCode.INTERNAL_ERROR;
        log.error("[UNHANDLED] path={}, ex={}", req.getRequestURI(), e.toString(), e);

        return ResponseEntity
                .status(ec.getStatus())
                .body(new ErrorResponse(
                        LocalDateTime.now(),
                        ec.getStatus().value(),
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
