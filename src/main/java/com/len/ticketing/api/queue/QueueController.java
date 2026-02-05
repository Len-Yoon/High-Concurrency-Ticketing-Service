package com.len.ticketing.api.queue;

import com.len.ticketing.api.queue.dto.QueueEnterRequest;
import com.len.ticketing.api.queue.dto.QueueStatusResponse;
import com.len.ticketing.application.queue.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping("/enter")
    public QueueStatusResponse enter(@RequestBody QueueEnterRequest req) {
        var r = queueService.enter(req.scheduleId(), req.userId());
        return new QueueStatusResponse(r.position(), r.canEnter(), r.token(), r.expiresAt());
    }

    @GetMapping("/status")
    public QueueStatusResponse status(@RequestParam long scheduleId, @RequestParam long userId) {
        var r = queueService.status(scheduleId, userId);
        return new QueueStatusResponse(r.position(), r.canEnter(), r.token(), r.expiresAt());
    }
}
