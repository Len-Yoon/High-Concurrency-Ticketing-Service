package com.len.ticketing.api.ticket.dto;

public record HoldSeatRequest(
        Long scheduleId,
        String seatNo,
        Long userId
) {}