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

@RequiredArgsConstructor
@Component
public class RedisQueueStore implements QueueStore {

    private final StringRedisTemplate redis;

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

    // ========== Waiting queue ==========
    @Override
    public long enterQueue(long scheduleId, long userId) {
        String key = queueKey(scheduleId);
        String member = String.valueOf(userId);

        Long rank = redis.opsForZSet().rank(key, member);
        if (rank == null) {
            // 공정성: 최초 진입 시점(오름차순) 기준
            redis.opsForZSet().add(key, member, System.currentTimeMillis());
            rank = redis.opsForZSet().rank(key, member);
        }
        return (rank == null) ? -1L : rank + 1;
    }

    @Override
    public long getPosition(long scheduleId, long userId) {
        String key = queueKey(scheduleId);
        String member = String.valueOf(userId);
        Long rank = redis.opsForZSet().rank(key, member);
        return (rank == null) ? -1L : rank + 1;
    }

    /**
     * (옵션) 기존 인터페이스에 남아있다면 유지.
     * QueueGate(allowedRank) 방식일 때만 사용.
     */
    @Override
    public boolean canEnter(long scheduleId, long userId, long allowedRank) {
        if (!queueEnabled) return true;
        long pos = getPosition(scheduleId, userId);
        return pos != -1L && pos <= allowedRank;
    }

    // ========== PASS token (atomic, consistent) ==========
    // Lua contract:
    // returns {token, expiresAtEpochMs, code}
    // code: HAS_PASS | WAIT | FULL | ISSUED
    private static final DefaultRedisScript<List> ISSUE_PASS_SCRIPT;
    static {
        String lua = ""
                + "-- KEYS[1]=queueKey\n"
                + "-- KEYS[2]=passZKey\n"
                + "-- KEYS[3]=passKey\n"
                + "-- KEYS[4]=seqKey\n"
                + "-- ARGV[1]=nowMs\n"
                + "-- ARGV[2]=capacity\n"
                + "-- ARGV[3]=ttlSec\n"
                + "-- ARGV[4]=userId\n"
                + "-- ARGV[5]=scheduleId\n"
                + "local queueKey = KEYS[1]\n"
                + "local passZKey = KEYS[2]\n"
                + "local passKey  = KEYS[3]\n"
                + "local seqKey   = KEYS[4]\n"
                + "local now      = tonumber(ARGV[1])\n"
                + "local cap      = tonumber(ARGV[2])\n"
                + "local ttl      = tonumber(ARGV[3])\n"
                + "local userId   = ARGV[4]\n"
                + "local schedId  = ARGV[5]\n"
                + "\n"
                + "-- safety: invalid cap/ttl => refuse\n"
                + "if (not cap) or cap <= 0 then return {'', '0', 'BAD_CAP'} end\n"
                + "if (not ttl) or ttl <= 0 then return {'', '0', 'BAD_TTL'} end\n"
                + "\n"
                + "-- cleanup expired pass\n"
                + "redis.call('ZREMRANGEBYSCORE', passZKey, '-inf', now)\n"
                + "\n"
                + "-- if already has pass token AND still tracked in passZ => return it\n"
                + "local existing = redis.call('GET', passKey)\n"
                + "if existing then\n"
                + "  local exp = redis.call('ZSCORE', passZKey, userId)\n"
                + "  if exp and tonumber(exp) > now then\n"
                + "    return {existing, tostring(exp), 'HAS_PASS'}\n"
                + "  end\n"
                + "  -- token exists but no valid passZ => cleanup\n"
                + "  redis.call('DEL', passKey)\n"
                + "end\n"
                + "\n"
                + "-- ensure user in waiting queue\n"
                + "local rank = redis.call('ZRANK', queueKey, userId)\n"
                + "if not rank then\n"
                + "  redis.call('ZADD', queueKey, now, userId)\n"
                + "  rank = redis.call('ZRANK', queueKey, userId)\n"
                + "end\n"
                + "\n"
                + "-- capacity full? (pass slots)\n"
                + "local active = redis.call('ZCARD', passZKey)\n"
                + "if active >= cap then\n"
                + "  return {'', '0', 'FULL'}\n"
                + "end\n"
                + "\n"
                + "-- issue only for top cap ranks (0-based rank < cap)\n"
                + "if (not rank) or tonumber(rank) >= cap then\n"
                + "  return {'', '0', 'WAIT'}\n"
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

    /**
     * 정합성 규칙:
     * - pass는 (passKey + passZKey score) 둘 다 유효해야 "있다"
     * - expiresAt은 passZ score가 source of truth
     * - Java에서 추가 SET 하지 않는다 (Lua가 원자적으로 SET/EX 수행)
     */
    @Override
    public QueuePass getPass(long scheduleId, long userId) {
        if (!queueEnabled) return null;

        String token = redis.opsForValue().get(passKey(scheduleId, userId));
        if (token == null || token.isBlank()) return null;

        Double expMs = redis.opsForZSet().score(passZKey(scheduleId), String.valueOf(userId));
        long now = System.currentTimeMillis();
        if (expMs == null || expMs.longValue() <= now) {
            // 불일치/만료 상태 정리
            redis.delete(passKey(scheduleId, userId));
            redis.opsForZSet().remove(passZKey(scheduleId), String.valueOf(userId));
            return null;
        }

        return new QueuePass(token, expMs.longValue());
    }

    @Override
    public QueuePass tryIssuePass(long scheduleId, long userId, long allowedSlots, long passTtlSeconds) {
        // 큐 OFF면 "토큰 없는 통과"
        // (BYPASS 문자열 토큰을 뿌리면 validatePass/게이트 로직이 오염됨)
        if (!queueEnabled) return null;

        long now = System.currentTimeMillis();

        List<?> res = redis.execute(
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

        return new QueuePass(token, expiresAt);
    }

    /**
     * validatePass는 "토큰 문자열 일치"만 신뢰한다.
     * passZ만 살아있다고 통과시키면 '토큰 정합성'이 깨져서
     * 네가 원하는 A안(키/포맷/TTL 통일) 검증이 불가능해진다.
     */
    @Override
    public boolean validatePass(long scheduleId, long userId, String token) {
        if (!queueEnabled) return true;
        if (token == null || token.isBlank()) return false;

        String stored = redis.opsForValue().get(passKey(scheduleId, userId));
        return stored != null && stored.equals(token);
    }

    /**
     * release는 passKey + passZ 둘 다 제거해야 정합성 유지됨.
     */
    @Override
    public void releasePass(long scheduleId, long userId) {
        redis.delete(passKey(scheduleId, userId));
        redis.opsForZSet().remove(passZKey(scheduleId), String.valueOf(userId));
    }
}