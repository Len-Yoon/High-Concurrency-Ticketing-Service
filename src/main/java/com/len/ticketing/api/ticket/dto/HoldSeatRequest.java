package com.len.ticketing.api.ticket.dto;

import jakarta.validation.constraints.NotNull;

public record HoldSeatRequest(
        @NotNull(message = "scheduleId는 필수입니다.")
        Long scheduleId,

        Long seatId,      // seatId 우선
        String seatNo,    // 하위호환

        @NotNull(message = "userId는 필수입니다.")
        Long userId,

        Boolean bypassQueue,
        String queueToken
) {}
