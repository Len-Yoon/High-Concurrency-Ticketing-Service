package com.len.ticketing.application.queue;

import com.len.ticketing.domain.queue.QueuePass;
import com.len.ticketing.domain.queue.QueueStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueStore queueStore;

    @Value("${ticketing.queue.capacity:100}")
    private long capacity;

    @Value("${ticketing.queue.pass-ttl-seconds:300}")
    private long passTtlSeconds;

    public QueueStatusDto enter(long scheduleId, long userId) {
        long pos = queueStore.enterQueue(scheduleId, userId);
        return new QueueStatusDto(pos, false, null, null);
    }

    public QueueStatusDto status(long scheduleId, long userId) {
        // 1) 이미 pass 있으면 바로 통과
        QueuePass pass = queueStore.getPass(scheduleId, userId);
        if (pass != null) {
            return new QueueStatusDto(0, true, pass.token(), pass.expiresAtEpochMs());
        }

        // 2) pass 없으면 waiting position 반환(없으면 자동 등록)
        long pos = queueStore.getPosition(scheduleId, userId);
        if (pos == -1) {
            pos = queueStore.enterQueue(scheduleId, userId);
        }
        return new QueueStatusDto(pos, false, null, null);
    }

    public record QueueStatusDto(long position, boolean canEnter, String token, Long expiresAt) {}
}
