package com.len.ticketing.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentMockSuccessRequest(
        String orderNo,
        String orderId
) {
    public String resolvedOrderNo() {
        if (orderNo != null && !orderNo.isBlank()) return orderNo;
        if (orderId != null && !orderId.isBlank()) return orderId;
        return null;
    }
}
