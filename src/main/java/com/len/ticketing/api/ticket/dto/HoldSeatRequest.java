package com.len.ticketing.api.ticket.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record HoldSeatRequest(
        @NotNull Long scheduleId,
        Long seatId,
        String seatNo,
        @NotNull Long userId
) {
    @AssertTrue(message = "seatId 또는 seatNo 중 하나는 필수입니다.")
    public boolean hasSeat() {
        return seatId != null || (seatNo != null && !seatNo.isBlank());
    }
}

