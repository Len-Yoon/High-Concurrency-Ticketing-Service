package com.len.ticketing.infra.reservation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConfirmedSeatGuardStore {

    private final JdbcTemplate jdbcTemplate;

    public void acquire(long scheduleId, String seatNo, long reservationId) {
        jdbcTemplate.update(
                "INSERT INTO confirmed_seat_guard(schedule_id, seat_no, reservation_id) VALUES (?, ?, ?)",
                scheduleId, seatNo, reservationId
        );
    }
}
