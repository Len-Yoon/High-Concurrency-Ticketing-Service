package com.len.ticketing.api.reservation;

import com.len.ticketing.application.ticket.TicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final TicketService ticketService;

    @PostMapping("/hold")
    public HoldResponse hold(@Valid @RequestBody HoldRequest request) {
        var result = ticketService.holdSeat(
                request.scheduleId(),
                request.seatNo(),
                request.userId()
        );
        return new HoldResponse(result.success(), result.message());
    }

    public record HoldRequest(
            @NotNull Long userId,
            @NotNull Long scheduleId,
            @NotBlank String seatNo
    ) {}

    public record HoldResponse(boolean success, String message) {}
}
