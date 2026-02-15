package com.len.ticketing.api.ticket;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 하위호환용 컨트롤러.
 * 신규 호출은 /api/reservations 사용 권장.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tickets")
@Deprecated
public class TicketController {

    private final ReservationController reservationController;

    @PostMapping("/hold")
    public Map<String, Object> hold(@RequestBody Map<String, Object> request) {
        return reservationController.hold(request);
    }

    @PostMapping("/release")
    public Map<String, Object> release(@RequestBody Map<String, Object> request) {
        return reservationController.release(request);
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody Map<String, Object> request) {
        return reservationController.confirm(request);
    }
}
