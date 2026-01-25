package com.len.ticketing.api.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfirmSeatRequest(
        @NotNull Long scheduleId,
        @NotBlank String seatNo,
        @NotNull Long userId
) {}
