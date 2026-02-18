package com.len.ticketing.application.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ticketing.queue.enabled", havingValue = "true", matchIfMissing = true)
public class QueueAdvancer {

    private final StringRedisTemplate redis;
    private final List<QueueAdvanceEngine> engines;

    @org.springframework.beans.factory.annotation.Value("${ticketing.queue.capacity:100}")
    private int capacity;

    @org.springframework.beans.factory.annotation.Value("${ticketing.queue.pass-ttl-seconds:300}")
    private int passTtlSeconds;

    @org.springframework.beans.factory.annotation.Value("${ticketing.queue.advance-lock-ttl-ms:5000}")
    private long lockTtlMs;

    @org.springframework.beans.factory.annotation.Value("${ticketing.queue.advance-engine:lua}")
    private String engineName;

    // waiting key는 "queue:{scheduleId}"라서 queue:* 스캔 후 필터링
    @org.springframework.beans.factory.annotation.Value("${ticketing.queue.waiting-scan-pattern:queue:*}")
    private String scanPattern;

    private final RedisScript<Long> unlockScript = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class
    );

    @Scheduled(fixedDelayString = "${ticketing.queue.advance-interval-ms:200}")
    public void advanceTick() {
        String lockVal = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(
                QueueRedisKeys.ADVANCE_LOCK_KEY,
                lockVal,
                lockTtlMs,
                TimeUnit.MILLISECONDS
        );
        if (locked == null || !locked) return;

        try {
            QueueAdvanceEngine engine = pickEngine(engineName);
            long nowMs = System.currentTimeMillis();

            Set<Long> scheduleIds = scanActiveScheduleIds();
            if (scheduleIds.isEmpty()) return;

            int total = 0;
            for (long scheduleId : scheduleIds) {
                total += engine.advance(scheduleId, nowMs, capacity, passTtlSeconds);
            }

            if (total > 0) {
                log.info("[QueueAdvancer] engine={}, schedules={}, advanced={}", engine.name(), scheduleIds.size(), total);
            }
        } catch (Exception e) {
            log.warn("[QueueAdvancer] failed", e);
        } finally {
            redis.execute(unlockScript, List.of(QueueRedisKeys.ADVANCE_LOCK_KEY), lockVal);
        }
    }

    private QueueAdvanceEngine pickEngine(String name) {
        return engines.stream()
                .filter(e -> e.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> engines.stream()
                        .filter(e -> e.name().equalsIgnoreCase("lua"))
                        .findFirst()
                        .orElseThrow());
    }

    private Set<Long> scanActiveScheduleIds() {
        Set<Long> ids = new HashSet<>();

        redis.execute((RedisCallback<Void>) connection -> {
            // Spring Data Redis 3.x 기준 안전한 scan
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(
                    ScanOptions.scanOptions().match(scanPattern).count(500).build()
            )) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next(), StandardCharsets.UTF_8);

                    // waiting key만: "queue:{digits}" 형태 허용 (pass 키 제외)
                    if (!key.startsWith(QueueRedisKeys.QUEUE_PREFIX)) continue;

                    String tail = key.substring(QueueRedisKeys.QUEUE_PREFIX.length());
                    if (tail.isBlank()) continue;

                    // "queue:12"는 OK, "queue:pass:z:12"는 ':' 포함 → 제외
                    if (tail.indexOf(':') != -1) continue;

                    try {
                        ids.add(Long.parseLong(tail));
                    } catch (NumberFormatException ignored) { }
                }
            }
            return null;
        });

        return ids;
    }
}
