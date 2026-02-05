package com.len.ticketing.api.ticket;

import com.len.ticketing.api.ticket.dto.ConfirmSeatRequest;
import com.len.ticketing.api.ticket.dto.HoldSeatRequest;
import com.len.ticketing.api.ticket.dto.HoldSeatResponse;
import com.len.ticketing.api.ticket.dto.ReleaseSeatRequest;
import com.len.ticketing.application.confirm.ConfirmCommandService;
import com.len.ticketing.application.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final ConfirmCommandService confirmCommandService;

    @PostMapping("/hold")
    public HoldSeatResponse hold(
            @RequestBody HoldSeatRequest request,
            @RequestHeader(value = "X-LOADTEST-BYPASS", required = false) String bypass,
            @RequestHeader(value = "X-QUEUE-TOKEN", required = false) String queueToken
    ) {
        boolean bypassQueue = "true".equalsIgnoreCase(bypass);

        var result = ticketService.holdSeat(
                request.scheduleId(),
                request.seatNo(),
                request.userId(),
                bypassQueue,
                queueToken
        );
        return new HoldSeatResponse(result.success(), result.message());
    }

    @PostMapping("/release")
    public void release(@RequestBody ReleaseSeatRequest request) {
        ticketService.releaseSeat(
                request.scheduleId(),
                request.seatNo(),
                request.userId()
        );
    }

    @PostMapping("/confirm")
    public void confirm(@RequestBody ConfirmSeatRequest req) {
        confirmCommandService.requestConfirm(req.scheduleId(), req.seatNo(), req.userId());
    }
}
