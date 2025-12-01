package com.len.ticketing.application.queue;

import com.len.ticketing.domain.queue.QueueStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class QueueService {

    private final QueueStore queueStore;

    // 나중에 설정값에서 빼고 싶으면 @Value로 뽑자
    private static final long DEFAULT_ALLOWED_RANK = 100L;

    @Transactional(readOnly = true)
    public QueueEnterResult enter(long scheduleId, long userId) {
        long position = queueStore.enterQueue(scheduleId, userId);
        boolean canEnter = queueStore.canEnter(scheduleId, userId, DEFAULT_ALLOWED_RANK);
        return new QueueEnterResult(position, canEnter);
    }

    @Transactional(readOnly = true)
    public QueueStatusResult getStatus(long scheduleId, long userId) {
        long position = queueStore.getPosition(scheduleId, userId);
        boolean canEnter = queueStore.canEnter(scheduleId, userId, DEFAULT_ALLOWED_RANK);
        return new QueueStatusResult(position, canEnter);
    }

    public record QueueEnterResult(long position, boolean canEnter) {}
    public record QueueStatusResult(long position, boolean canEnter) {}
}
