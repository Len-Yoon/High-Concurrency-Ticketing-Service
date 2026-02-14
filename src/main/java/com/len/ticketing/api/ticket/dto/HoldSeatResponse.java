package com.len.ticketing.api.ticket.dto;

public record HoldSeatResponse(
        boolean success,
        String message,
        Long reservationId
) {
    // 기존 2인자 호출도 깨지지 않게 호환 생성자 유지
    public HoldSeatResponse(boolean success, String message) {
        this(success, message, null);
    }
}
