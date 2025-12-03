package com.len.ticketing.api.payment.dto;

public record PaymentReadyRequest(
        Long userId,
        Long scheduleId,
        String seatNo
) {}
