package com.len.ticketing.api.reservation;

import com.len.ticketing.api.ticket.dto.HoldSeatRequest;
import com.len.ticketing.api.ticket.dto.HoldSeatResponse;
import com.len.ticketing.application.ticket.TicketService;
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

    // 현재 컨트롤러에서 미사용이지만 남겨둬도 무방
    public record HoldRequest(
            @NotNull Long userId,
            @NotNull Long scheduleId,
            @NotBlank String seatNo
    ) {}

    public record HoldResponse(boolean success, String message) {}
}
