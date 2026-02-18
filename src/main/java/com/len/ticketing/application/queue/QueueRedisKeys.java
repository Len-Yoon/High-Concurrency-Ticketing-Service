package com.len.ticketing.application.queue;

public final class QueueRedisKeys {

    // ✅ 기존 RedisQueueStore 키 규칙에 맞춤
    public static final String QUEUE_PREFIX = "queue:";           // waiting zset: queue:{scheduleId}
    public static final String PASS_Z_PREFIX = "queue:pass:z:";   // pass zset: queue:pass:z:{scheduleId}
    public static final String PASS_PREFIX = "queue:pass:";       // token: queue:pass:{scheduleId}:{userId}
    public static final String PASS_SEQ_PREFIX = "queue:pass:seq:";// seq: queue:pass:seq:{scheduleId}

    // 스케줄러 락(전역)
    public static final String ADVANCE_LOCK_KEY = "queue:advance:lock";

    private QueueRedisKeys() {}

    public static String waitingKey(long scheduleId) {
        return QUEUE_PREFIX + scheduleId;
    }

    public static String passZKey(long scheduleId) {
        return PASS_Z_PREFIX + scheduleId;
    }

    public static String tokenKeyPrefix(long scheduleId) {
        // script에서 + userId 로 붙일 prefix
        return PASS_PREFIX + scheduleId + ":";
    }

    public static String tokenKey(long scheduleId, long userId) {
        return PASS_PREFIX + scheduleId + ":" + userId;
    }

    public static String seqKey(long scheduleId) {
        return PASS_SEQ_PREFIX + scheduleId;
    }
}
