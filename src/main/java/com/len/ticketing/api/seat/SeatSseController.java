package com.len.ticketing.api.seat;

import com.len.ticketing.infra.sse.SeatSseHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/seats")
public class SeatSseController {

    private final SeatSseHub hub;

    public SeatSseController(SeatSseHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam long scheduleId) {
        return hub.register(scheduleId);
    }
}
