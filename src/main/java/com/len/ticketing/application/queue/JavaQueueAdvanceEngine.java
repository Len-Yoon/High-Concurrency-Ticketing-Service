package com.len.ticketing.application.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JavaQueueAdvanceEngine implements QueueAdvanceEngine {

    private final StringRedisTemplate redis;

    @Override
    public int advance(long scheduleId, long nowMs, int capacity, long passTtlSeconds) {
        String waitingKey = QueueRedisKeys.waitingKey(scheduleId);
        String passZKey = QueueRedisKeys.passZKey(scheduleId);

        // 만료 정리 (score=expireAtMs)
        redis.opsForZSet().removeRangeByScore(passZKey, 0, nowMs);

        Long passCount = redis.opsForZSet().zCard(passZKey);
        int deficit = capacity - (passCount == null ? 0 : passCount.intValue());
        if (deficit <= 0) return 0;

        Duration ttl = Duration.ofSeconds(passTtlSeconds);

        int advanced = 0;
        for (int i = 0; i < deficit; i++) {
            ZSetOperations.TypedTuple<String> popped = redis.opsForZSet().popMin(waitingKey);
            if (popped == null || popped.getValue() == null) break;

            String userId = popped.getValue();

            long expireAt = nowMs + passTtlSeconds * 1000L;
            redis.opsForZSet().add(passZKey, userId, expireAt);

            String token = nowMs + ":" + UUID.randomUUID() + ":" + userId;
            redis.opsForValue().set(QueueRedisKeys.tokenKeyPrefix(scheduleId) + userId, token, ttl);

            advanced++;
        }
        return advanced;
    }

    @Override
    public String name() {
        return "java";
    }
}
