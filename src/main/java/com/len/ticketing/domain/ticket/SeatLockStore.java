package com.len.ticketing.domain.ticket;

public interface SeatLockStore {

    /**
     * 좌석 선점 시도
     * @return true: 선점 성공 / false: 이미 누가 잡고 있음
     */
    boolean lockSeat(long scheduleId, String seatNo, long userId, long ttlSeconds);

    /**
     * 선점 해제 (owner만 해제 가능)
     */
    void releaseSeat(long scheduleId, String seatNo, long userId);

    /**
     * 현재 이 좌석을 선점한 사용자 ID (없으면 null)
     */
    Long getLockOwner(long scheduleId, String seatNo);
}
