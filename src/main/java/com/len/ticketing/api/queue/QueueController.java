package com.len.ticketing.api.queue;

import com.len.ticketing.api.queue.dto.QueueEnterRequest;
import com.len.ticketing.api.queue.dto.QueueStatusResponse;
import com.len.ticketing.application.queue.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;

    // 대기열 진입
    @PostMapping("/enter")
    public QueueStatusResponse enter(@RequestBody QueueEnterRequest request) {
        var result = queueService.enter(request.scheduleId(), request.userId());
        return new QueueStatusResponse(result.position(), result.canEnter());
    }

    // 상태 조회
    @GetMapping("/status")
    public QueueStatusResponse status(
            @RequestParam Long scheduleId,
            @RequestParam Long userId
    ) {
        var result = queueService.getStatus(scheduleId, userId);
        return new QueueStatusResponse(result.position(), result.canEnter());
    }
}
