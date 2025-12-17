package com.len.ticketing.application.reservation;

public class SeatAlreadyReservedException extends RuntimeException
{
    public SeatAlreadyReservedException(String message) {
        super(message);
    }
}
