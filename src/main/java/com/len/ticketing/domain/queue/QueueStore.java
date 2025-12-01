package com.len.ticketing.domain.queue;

public interface QueueStore {

    /**
     * 대기열 진입
     * @return 현재 순번 (1부터 시작)
     */
    long enterQueue(long scheduleId, long userId);

    /**
     * 현재 순번 조회 (없으면 -1)
     */
    long getPosition(long scheduleId, long userId);

    /**
     * allowedRank 이내면 true
     */
    boolean canEnter(long scheduleId, long userId, long allowedRank);
}
