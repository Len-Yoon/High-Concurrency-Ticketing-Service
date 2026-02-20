package com.len.ticketing.domain.queue;

public interface QueueStore {
    long enterQueue(long scheduleId, long userId);
    long getPosition(long scheduleId, long userId);

    QueuePass getPass(long scheduleId, long userId);
    QueuePass tryIssuePass(long scheduleId, long userId, long allowedSlots, long passTtlSeconds);
    boolean validatePass(long scheduleId, long userId, String token);
    void releasePass(long scheduleId, long userId);

    boolean canEnter(long scheduleId, long userId, long allowedRank);
}
