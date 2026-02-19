package com.len.ticketing.infra.redis;

import com.len.ticketing.domain.queue.QueuePass;
import com.len.ticketing.domain.queue.QueueStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Component
public class RedisQueueStore implements QueueStore {

    private final StringRedisTemplate redisTemplate;

    @Value("${ticketing.queue.enabled:true}")
    private boolean queueEnabled;

    // ========== Key helpers ==========
    private String queueKey(long scheduleId) {
        return "queue:" + scheduleId;
    }

    private String passZKey(long scheduleId) {
        return "queue:pass:z:" + scheduleId;
    }

    private String passKey(long scheduleId, long userId) {
        return "queue:pass:" + scheduleId + ":" + userId;
    }

    private String passSeqKey(long scheduleId) {
        return "queue:pass:seq:" + scheduleId;
    }

    // ========== 기존: 대기열 ==========
    @Override
    public long enterQueue(long scheduleId, long userId) {
        String key = queueKey(scheduleId);
        String member = String.valueOf(userId);

        // ✅ 멱등: 이미 있으면 기존 score를 유지해야 공정함
        Long rank = redisTemplate.opsForZSet().rank(key, member);
        if (rank == null) {
            redisTemplate.opsForZSet().add(key, member, System.currentTimeMillis());
            rank = redisTemplate.opsForZSet().rank(key, member);
        }

        return (rank == null) ? -1L : rank + 1; // 1부터 시작
    }

    @Override
    public long getPosition(long scheduleId, long userId) {
        String key = queueKey(scheduleId);
        String member = String.valueOf(userId);

        Long rank = redisTemplate.opsForZSet().rank(key, member);
        return (rank == null) ? -1L : rank + 1;
    }

    @Override
    public boolean canEnter(long scheduleId, long userId, long allowedRank) {
        // ✅ 전역 큐 OFF(local/loadtest)
        if (!queueEnabled) return true;

        long position = getPosition(scheduleId, userId);
        return position != -1L && position <= allowedRank;
    }

    // ========== 실전형: PASS 토큰 ==========
    // tryIssuePass: 만료 정리 + 상위 allowedSlots 체크 + 슬롯 체크 + 토큰 발급(원자)
    private static final DefaultRedisScript<List> ISSUE_PASS_SCRIPT;
    static {
        String lua = ""
                + "-- KEYS[1]=queueKey\n"
                + "-- KEYS[2]=passZKey\n"
                + "-- KEYS[3]=passKey\n"
                + "-- KEYS[4]=seqKey\n"
                + "-- ARGV[1]=nowMs\n"
                + "-- ARGV[2]=allowedSlots\n"
                + "-- ARGV[3]=ttlSec\n"
                + "-- ARGV[4]=userId\n"
                + "-- ARGV[5]=scheduleId\n"
                + "local queueKey = KEYS[1]\n"
                + "local passZKey = KEYS[2]\n"
                + "local passKey  = KEYS[3]\n"
                + "local seqKey   = KEYS[4]\n"
                + "local now      = tonumber(ARGV[1])\n"
                + "local allowed  = tonumber(ARGV[2])\n"
                + "local ttl      = tonumber(ARGV[3])\n"
                + "local userId   = ARGV[4]\n"
                + "local schedId  = ARGV[5]\n"
                + "\n"
                + "-- already has pass\n"
                + "local existing = redis.call('GET', passKey)\n"
                + "if existing then\n"
                + "  local ttlLeft = redis.call('TTL', passKey)\n"
                + "  if ttlLeft and ttlLeft > 0 then\n"
                + "    return {existing, tostring(now + ttlLeft * 1000), 'HAS_PASS'}\n"
                + "  end\n"
                + "  redis.call('DEL', passKey)\n"
                + "end\n"
                + "\n"
                + "-- cleanup expired pass\n"
                + "redis.call('ZREMRANGEBYSCORE', passZKey, '-inf', now)\n"
                + "\n"
                + "-- ensure user in queue (idempotent)\n"
                + "local rank = redis.call('ZRANK', queueKey, userId)\n"
                + "if not rank then\n"
                + "  redis.call('ZADD', queueKey, now, userId)\n"
                + "  rank = redis.call('ZRANK', queueKey, userId)\n"
                + "end\n"
                + "\n"
                + "-- rank cut: only top allowed can get pass\n"
                + "if rank and tonumber(rank) >= allowed then\n"
                + "  return {'', '0', 'WAIT'}\n"
                + "end\n"
                + "\n"
                + "-- slot check\n"
                + "local active = redis.call('ZCARD', passZKey)\n"
                + "if active >= allowed then\n"
                + "  return {'', '0', 'FULL'}\n"
                + "end\n"
                + "\n"
                + "-- issue token\n"
                + "local seq = redis.call('INCR', seqKey)\n"
                + "local token = schedId .. ':' .. userId .. ':' .. now .. ':' .. seq\n"
                + "local expiresAt = now + (ttl * 1000)\n"
                + "redis.call('SET', passKey, token, 'EX', ttl)\n"
                + "redis.call('ZADD', passZKey, expiresAt, userId)\n"
                + "redis.call('ZREM', queueKey, userId)\n"
                + "return {token, tostring(expiresAt), 'ISSUED'}\n";

        ISSUE_PASS_SCRIPT = new DefaultRedisScript<>();
        ISSUE_PASS_SCRIPT.setScriptText(lua);
        ISSUE_PASS_SCRIPT.setResultType(List.class);
    }

    @Override
    public QueuePass getPass(long scheduleId, long userId) {
        // 큐 OFF면 토큰 자체가 필요 없으니 null 반환해도 됨
        if (!queueEnabled) return null;

        String key = passKey(scheduleId, userId);
        String token = redisTemplate.opsForValue().get(key);
        if (token == null || token.isBlank()) return null;

        Long ttlSec = redisTemplate.getExpire(key); // seconds
        if (ttlSec == null || ttlSec <= 0) {
            redisTemplate.delete(key);
            return null;
        }

        long expiresAt = System.currentTimeMillis() + (ttlSec * 1000);
        // passZ는 tryIssuePass에서 정리되도록 두되, 누락됐을 때도 문제 없게 유지
        return new QueuePass(token, expiresAt);
    }

    @Override
    public QueuePass tryIssuePass(long scheduleId, long userId, long allowedSlots, long passTtlSeconds) {
        // 큐 OFF면 항상 통과시키되, 테스트 편의상 가짜 토큰 하나 반환
        if (!queueEnabled) {
            long expiresAt = System.currentTimeMillis() + (passTtlSeconds * 1000);
            return new QueuePass("BYPASS", expiresAt);
        }

        long now = System.currentTimeMillis();

        List<?> res = redisTemplate.execute(
                ISSUE_PASS_SCRIPT,
                List.of(
                        queueKey(scheduleId),
                        passZKey(scheduleId),
                        passKey(scheduleId, userId),
                        passSeqKey(scheduleId)
                ),
                String.valueOf(now),
                String.valueOf(allowedSlots),
                String.valueOf(passTtlSeconds),
                String.valueOf(userId),
                String.valueOf(scheduleId)
        );

        if (res == null || res.size() < 2) return null;

        String token = Objects.toString(res.get(0), "");
        long expiresAt = Long.parseLong(Objects.toString(res.get(1), "0"));

        if (token.isBlank() || expiresAt <= 0) return null;

        redisTemplate.opsForValue().set(passKey(scheduleId, userId), token, passTtlSeconds, TimeUnit.SECONDS);
        return new QueuePass(token, expiresAt);
    }

    @Override
    public boolean validatePass(long scheduleId, long userId, String token) {
        if (!queueEnabled) return true;
        if (token == null || token.isBlank()) return false;

        String pk = passKey(scheduleId, userId);
        String stored = redisTemplate.opsForValue().get(pk);
        if (stored != null) {
            return stored.equals(token);
        }

        // backward/bug-safe fallback: passKey가 없더라도 passZKey에 살아있으면 통과
        Double expMs = redisTemplate.opsForZSet().score(passZKey(scheduleId), String.valueOf(userId));
        return expMs != null && expMs.longValue() > System.currentTimeMillis();
    }

    @Override
    public void releasePass(long scheduleId, long userId) {
        // passZ에서 제거
        redisTemplate.opsForZSet().remove(passZKey(scheduleId), String.valueOf(userId));
    }
}
