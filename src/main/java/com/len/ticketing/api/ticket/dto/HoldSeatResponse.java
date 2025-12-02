package com.len.ticketing.api.ticket.dto;

public record HoldSeatResponse(
        boolean success,
        String message
) {}