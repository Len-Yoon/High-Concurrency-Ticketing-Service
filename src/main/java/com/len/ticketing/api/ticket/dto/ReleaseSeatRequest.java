package com.len.ticketing.api.ticket.dto;

public record ReleaseSeatRequest(
        Long scheduleId,
        String seatNo,
        Long userId
) {}
