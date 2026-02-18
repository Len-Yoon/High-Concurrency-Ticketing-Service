package com.len.ticketing.application.queue;

public final class QueueRedisKeys {

    // ✅ 기존 RedisQueueStore 키 규칙에 맞춤
    // waiting zset: queue:{scheduleId}
    public static final String QUEUE_PREFIX = "queue:";

    // pass zset(만료 포함): queue:pass:z:{scheduleId}
    public static final String PASS_Z_PREFIX = "queue:pass:z:";

    // token string: queue:pass:{scheduleId}:{userId}
    public static final String PASS_PREFIX = "queue:pass:";

    // token seq: queue:pass:seq:{scheduleId}
    public static final String PASS_SEQ_PREFIX = "queue:pass:seq:";

    // scheduler lock(전역)
    public static final String ADVANCE_LOCK_KEY = "queue:advance:lock";

    private QueueRedisKeys() {}

    public static String waitingKey(long scheduleId) {
        return QUEUE_PREFIX + scheduleId;
    }

    public static String passZKey(long scheduleId) {
        return PASS_Z_PREFIX + scheduleId;
    }

    public static String tokenKeyPrefix(long scheduleId) {
        return PASS_PREFIX + scheduleId + ":";
    }

    public static String tokenKey(long scheduleId, long userId) {
        return PASS_PREFIX + scheduleId + ":" + userId;
    }

    public static String seqKey(long scheduleId) {
        return PASS_SEQ_PREFIX + scheduleId;
    }
}
