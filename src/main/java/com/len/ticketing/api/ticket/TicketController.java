package com.len.ticketing.api.ticket;

import com.len.ticketing.api.reservation.ReservationController;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ticket")
public class TicketController {

    private final ReservationController reservationController;

    public TicketController(ReservationController reservationController) {
        this.reservationController = reservationController;
    }

    @PostMapping(value = "/hold", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> hold(
            @RequestBody Map<String, Object> request,
            @RequestHeader(name = "X-QUEUE-TOKEN", required = false) String headerQueueToken
    ) {
        // header token도 같이 전달해줘야 ReservationController가 fallback 처리 가능
        return reservationController.hold(request, headerQueueToken);
    }
}
