package com.len.ticketing.api.ticket.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record HoldSeatRequest(
        @NotNull(message = "scheduleId는 필수입니다.")
        Long scheduleId,

        Long seatId,     // 신규: ID 기반 요청 지원

        String seatNo,   // 기존: 하위호환 유지

        @NotNull(message = "userId는 필수입니다.")
        Long userId
) {
    @AssertTrue(message = "seatId 또는 seatNo 중 하나는 필수입니다.")
    public boolean hasSeat() {
        return seatId != null || (seatNo != null && !seatNo.isBlank());
    }
}
