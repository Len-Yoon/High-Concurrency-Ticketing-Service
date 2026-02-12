package com.len.ticketing.api.reservation;

import com.len.ticketing.api.ticket.dto.HoldSeatRequest;
import com.len.ticketing.api.ticket.dto.HoldSeatResponse;
import com.len.ticketing.application.ticket.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final TicketService ticketService;

    @PostMapping("/hold")
    public HoldSeatResponse hold(
            @Valid @RequestBody HoldSeatRequest request,
            @RequestHeader(value = "X-LOADTEST-BYPASS", required = false) String bypass,
            @RequestHeader(value = "X-QUEUE-TOKEN", required = false) String queueToken
    ) {
        boolean bypassQueue = "true".equalsIgnoreCase(bypass);

        var result = (request.seatId() != null)
                ? ticketService.holdSeatById(
                request.scheduleId(),
                request.seatId(),
                request.userId(),
                bypassQueue,
                queueToken
        )
                : ticketService.holdSeat(
                request.scheduleId(),
                request.seatNo(),
                request.userId(),
                bypassQueue,
                queueToken
        );

        return new HoldSeatResponse(result.success(), result.message());
    }
}
