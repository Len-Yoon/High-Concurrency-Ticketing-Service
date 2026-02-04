package com.len.ticketing.application.queue;

import com.len.ticketing.domain.queue.QueueStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        QueuePass pass = queueStore.tryIssuePass(scheduleId, userId, capacity, passTtlSeconds);
        if (pass != null) {
            return new QueueStatusDto(0, true, pass.token(), pass.expiresAtEpochMs());
        }
        return new QueueStatusDto(pos, false, null, null);
    }

    public QueueStatusDto status(long scheduleId, long userId) {
        // status도 동일하게 “발급 시도”를 해줘야 슬롯이 비면 바로 들어감
        long pos = queueStore.getPosition(scheduleId, userId);
        if (pos == -1) {
            // 혹시 큐에 없으면 진입시키고 상태 반환(옵션)
            pos = queueStore.enterQueue(scheduleId, userId);
        }

        QueuePass pass = queueStore.tryIssuePass(scheduleId, userId, capacity, passTtlSeconds);
        if (pass != null) {
            return new QueueStatusDto(0, true, pass.token(), pass.expiresAtEpochMs());
        }
        return new QueueStatusDto(pos, false, null, null);
    }

    public record QueueStatusDto(long position, boolean canEnter, String token, Long expiresAt) {}
}
