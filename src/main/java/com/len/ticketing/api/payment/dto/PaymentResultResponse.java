package com.len.ticketing.api.payment.dto;

public record PaymentResultResponse(
        boolean success,
        String message
) {}
