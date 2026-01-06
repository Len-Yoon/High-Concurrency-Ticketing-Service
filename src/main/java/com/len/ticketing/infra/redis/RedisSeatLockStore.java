package com.len.ticketing.infra.redis;

import com.len.ticketing.domain.ticket.SeatLockStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 좌석 선점(락) 저장소.
 *
 * - lock: SET key value NX EX
 * - release: value(소유자) 확인 후 DEL (Lua로 원자 처리)
 */
@Component
@RequiredArgsConstructor
public class RedisSeatLockStore implements SeatLockStore {

    private final StringRedisTemplate redisTemplate;

    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class
    );

    private String lockKey(long scheduleId, String seatNo) {
        return "seat:lock:" + scheduleId + ":" + seatNo;
    }

    @Override
    public boolean lockSeat(long scheduleId, String seatNo, long userId, long ttlSeconds) {
        String key = lockKey(scheduleId, seatNo);
        String value = String.valueOf(userId);
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void releaseSeat(long scheduleId, String seatNo, long userId) {
        String key = lockKey(scheduleId, seatNo);
        String value = String.valueOf(userId);
        // 소유자 아니면 아무 것도 안 함
        redisTemplate.execute(RELEASE_IF_OWNER_SCRIPT, List.of(key), value);
    }

    @Override
    public Long getLockOwner(long scheduleId, String seatNo) {
        String key = lockKey(scheduleId, seatNo);
        String v = redisTemplate.opsForValue().get(key);
        if (v == null || v.isBlank()) return null;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
