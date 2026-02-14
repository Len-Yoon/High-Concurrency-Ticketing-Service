package com.len.ticketing.api.ticket;

import com.len.ticketing.api.ticket.dto.HoldSeatRequest;
import com.len.ticketing.api.ticket.dto.HoldSeatResponse;
import com.len.ticketing.application.ticket.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    @PostMapping("/hold")
    public HoldSeatResponse hold(
            @Valid @RequestBody HoldSeatRequest request,
            @RequestHeader(value = "X-LOADTEST-BYPASS", required = false) String bypass,
            @RequestHeader(value = "X-QUEUE-TOKEN", required = false) String queueToken
    ) {
        boolean bypassQueue = "true".equalsIgnoreCase(bypass) || Boolean.TRUE.equals(request.bypassQueue());
        String token = (request.queueToken() != null && !request.queueToken().isBlank())
                ? request.queueToken()
                : queueToken;

        var result = (request.seatId() != null)
                ? ticketService.holdSeatById(
                request.scheduleId(),
                request.seatId(),
                request.userId(),
                bypassQueue,
                token
        )
                : ticketService.holdSeat(
                request.scheduleId(),
                request.seatNo(),
                request.userId(),
                bypassQueue,
                token
        );

        return new HoldSeatResponse(result.success(), result.message(), result.reservationId());
    }
}
