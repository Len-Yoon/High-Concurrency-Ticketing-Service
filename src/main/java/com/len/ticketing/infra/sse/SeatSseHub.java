package com.len.ticketing.infra.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * scheduleId 별로 SSE 연결(SseEmitter)들을 보관하고,
 * hello/ping/seat 이벤트를 브로드캐스트하는 허브.
 *
 * ✅ 중요
 * - 클라이언트(브라우저/ curl)가 끊기는 건 정상 상황 -> IOException 발생
 * - publish에서 IOException/IllegalStateException 발생 시 emitter 제거해야 로그 폭탄/요청 실패를 막을 수 있음
 */
@Component
public class SeatSseHub {

    // scheduleId -> emitters
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> room = new ConcurrentHashMap<>();

    /**
     * SSE 구독(연결 생성)
     */
    public SseEmitter subscribe(Long scheduleId) {
        // timeout 0 = 무제한(필요하면 30분 등으로 설정 가능)
        SseEmitter emitter = new SseEmitter(0L);

        room.computeIfAbsent(scheduleId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> remove(scheduleId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        // ✅ 연결 확인용 hello
        try {
            emitter.send(SseEmitter.event()
                    .name("hello")
                    .data(Map.of("ok", true)));
        } catch (IOException | IllegalStateException e) {
            cleanup.run();
        }

        return emitter;
    }

    /**
     * 좌석 변경 이벤트 발행 (event: seat)
     */
    public void publish(Long scheduleId, Object payload) {
        List<SseEmitter> emitters = room.get(scheduleId);
        if (emitters == null || emitters.isEmpty()) return;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("seat")
                        .data(payload));
            } catch (IOException | IllegalStateException e) {
                // ✅ 끊긴 연결은 정리 (정상 상황)
                remove(scheduleId, emitter);
            }
        }
    }

    /**
     * keep-alive ping (event: ping)
     * - Controller에서 @Scheduled로 주기 호출하면 브라우저가 프록시/네트워크에 의해 끊기는 걸 줄일 수 있음
     */
    public void ping(Long scheduleId) {
        List<SseEmitter> emitters = room.get(scheduleId);
        if (emitters == null || emitters.isEmpty()) return;

        Map<String, String> payload = Map.of("at", LocalDateTime.now().toString());

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("ping")
                        .data(payload));
            } catch (IOException | IllegalStateException e) {
                remove(scheduleId, emitter);
            }
        }
    }

    /**
     * 모든 scheduleId 대상으로 ping 브로드캐스트
     */
    public void pingAll() {
        for (Long scheduleId : room.keySet()) {
            ping(scheduleId);
        }
    }

    /**
     * emitter 제거 + room 정리
     */
    private void remove(Long scheduleId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = room.get(scheduleId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                room.remove(scheduleId);
            }
        }
        try {
            emitter.complete();
        } catch (Exception ignore) {
        }
    }
}
