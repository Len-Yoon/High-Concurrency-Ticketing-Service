package com.len.ticketing.infra.sse;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SeatSseHub {

    private final Map<Long, List<SseEmitter>> emittersBySchedule = new ConcurrentHashMap<>();

    public SseEmitter register(long scheduleId) {
        // 0L = timeout 없음 (브라우저가 끊을 때까지)
        SseEmitter emitter = new SseEmitter(0L);

        emittersBySchedule.computeIfAbsent(scheduleId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(scheduleId, emitter));
        emitter.onTimeout(() -> remove(scheduleId, emitter));
        emitter.onError(e -> remove(scheduleId, emitter));

        // 첫 연결 확인용 이벤트(선택)
        try {
            emitter.send(SseEmitter.event()
                    .name("hello")
                    .data("{\"ok\":true}", MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {
        }

        return emitter;
    }

    public void publish(long scheduleId, SeatChangedEvent event) {
        List<SseEmitter> list = emittersBySchedule.get(scheduleId);
        if (list == null || list.isEmpty()) return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("seat")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                remove(scheduleId, emitter);
            }
        }
    }

    private void remove(long scheduleId, SseEmitter emitter) {
        List<SseEmitter> list = emittersBySchedule.get(scheduleId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emittersBySchedule.remove(scheduleId);
    }

    // 연결 유지(프록시/브라우저가 가끔 SSE 끊는 거 방지)
    @Scheduled(fixedRate = 15000)
    public void keepAlive() {
        for (Map.Entry<Long, List<SseEmitter>> e : emittersBySchedule.entrySet()) {
            for (SseEmitter emitter : e.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("ping")
                            .data(new Ping(LocalDateTime.now()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    remove(e.getKey(), emitter);
                }
            }
        }
    }

    public record Ping(LocalDateTime at) {}
}
