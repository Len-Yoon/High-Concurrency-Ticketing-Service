package com.len.ticketing.api.concert.dto;

import com.len.ticketing.domain.concert.Concert;

public record ConcertResponse(
        Long id,
        String title,
        String description
) {
    public static ConcertResponse from(Concert concert) {
        return new ConcertResponse(
                concert.getId(),
                concert.getTitle(),
                concert.getDescription()
        );
    }
}
