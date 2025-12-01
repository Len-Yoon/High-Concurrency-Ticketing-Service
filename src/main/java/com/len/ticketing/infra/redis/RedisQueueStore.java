package com.len.ticketing.infra.redis;

import com.len.ticketing.domain.queue.QueueStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@RequiredArgsConstructor
@Component
public class RedisQueueStore implements QueueStore {

    private final StringRedisTemplate redisTemplate;

    // Redis 키 생성
    private String queueKey(long scheduleId) {
        return "queue:" + scheduleId;
    }

    @Override
    public long enterQueue(long scheduleId, long userId) {
        String key = queueKey(scheduleId);
        String member = String.valueOf(userId);

        // score: 진입 시간 (epoch milli)
        double score = (double) Instant.now().toEpochMilli();

        // ZADD
        redisTemplate.opsForZSet().add(key, member, score);

        // 랭크(0부터 시작)
        Long rank = redisTemplate.opsForZSet().rank(key, member);
        if (rank == null) {
            return -1L;
        }
        // 1부터 시작하도록 +1
        return rank + 1;
    }

    @Override
    public long getPosition(long scheduleId, long userId) {
        String key = queueKey(scheduleId);
        String member = String.valueOf(userId);

        Long rank = redisTemplate.opsForZSet().rank(key, member);
        if (rank == null) {
            return -1L;
        }
        return rank + 1;
    }

    @Override
    public boolean canEnter(long scheduleId, long userId, long allowedRank) {
        long position = getPosition(scheduleId, userId);
        if (position == -1L) {
            return false;
        }
        return position <= allowedRank;
    }
}
