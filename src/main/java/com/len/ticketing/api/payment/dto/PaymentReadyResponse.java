package com.len.ticketing.api.payment.dto;

public record PaymentReadyResponse(
        String orderNo,
        int amount,
        String message
) {}
