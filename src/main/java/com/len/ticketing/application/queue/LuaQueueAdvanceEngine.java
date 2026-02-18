package com.len.ticketing.application.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LuaQueueAdvanceEngine implements QueueAdvanceEngine {

    private final StringRedisTemplate redis;

    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>() {{
        setLocation(new ClassPathResource("redis/queue_advance.lua"));
        setResultType(Long.class);
    }};

    @Override
    public int advance(long scheduleId, long nowMs, int capacity, int passTtlSeconds) {
        long passTtlMs = passTtlSeconds * 1000L;

        Long advanced = redis.execute(
                script,
                List.of(
                        QueueRedisKeys.waitingKey(scheduleId),
                        QueueRedisKeys.passZKey(scheduleId)
                ),
                String.valueOf(nowMs),
                String.valueOf(capacity),
                String.valueOf(passTtlMs),
                QueueRedisKeys.tokenKeyPrefix(scheduleId),
                QueueRedisKeys.seqKey(scheduleId)
        );

        return advanced == null ? 0 : advanced.intValue();
    }

    @Override
    public String name() {
        return "lua";
    }
}
