package com.len.ticketing.domain.ticket;
public interface SeatLockStore {

    boolean lockSeat(long scheduleId, String seatNo, long userId, long ttlSeconds);

    void releaseSeat(long scheduleId, String seatNo, long userId);

    Long getLockOwner(long scheduleId, String seatNo);
}
