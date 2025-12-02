package com.len.ticketing.infra.redis;

import com.len.ticketing.domain.ticket.SeatLockStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@RequiredArgsConstructor
@Component
public class RedisSeatLockStore implements SeatLockStore {

    private final StringRedisTemplate redisTemplate;

    private String seatLockKey(long scheduleId, String seatNo) {
        return "seat:lock:" + scheduleId + ":" + seatNo;
    }

    @Override
    public boolean lockSeat(long scheduleId, String seatNo, long userId, long ttlSeconds) {
        String key = seatLockKey(scheduleId, seatNo);
        String value = String.valueOf(userId);

        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, value, Duration.ofSeconds(ttlSeconds));

        // null 이면 실패로 취급
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void releaseSeat(long scheduleId, String seatNo, long userId) {
        String key = seatLockKey(scheduleId, seatNo);
        String current = redisTemplate.opsForValue().get(key);

        // 내가 잡은 좌석일 때만 지움
        if (current != null && current.equals(String.valueOf(userId))) {
            redisTemplate.delete(key);
        }
    }

    @Override
    public Long getLockOwner(long scheduleId, String seatNo) {
        String key = seatLockKey(scheduleId, seatNo);
        String current = redisTemplate.opsForValue().get(key);
        if (current == null) {
            return null;
        }
        try {
            return Long.parseLong(current);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
