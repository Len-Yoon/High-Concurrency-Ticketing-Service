package com.len.ticketing.infra.outbox;

import com.len.ticketing.domain.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${ticketing.outbox.batch-size:100}")
    private int batchSize;

    @Value("${ticketing.outbox.publish-timeout-ms:3000}")
    private long publishTimeoutMs;

    @Scheduled(fixedDelayString = "${ticketing.outbox.publish-interval-ms:300}")
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> batch = outboxEventRepository.lockPendingBatch(batchSize);
        if (batch.isEmpty()) return;

        int success = 0;
        int retry = 0;
        int failed = 0;

        for (OutboxEvent e : batch) {
            try {
                // ack 대기 (무한대기 방지)
                kafkaTemplate.send(e.getTopic(), e.getEventKey(), e.getPayload())
                        .get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                e.markPublished();
                success++;

            } catch (Exception ex) {
                // OutboxEvent에 markRetryOrFail(String) 구현되어 있다는 전제
                e.markRetryOrFail(rootMessage(ex));

                if ("FAILED".equals(e.getStatus().name())) {
                    failed++;
                    log.error("Outbox publish failed permanently. eventId={}, topic={}, key={}, retryCount={}, err={}",
                            e.getEventId(), e.getTopic(), e.getEventKey(), e.getRetryCount(), rootMessage(ex));
                } else {
                    retry++;
                    log.warn("Outbox publish retry scheduled. eventId={}, topic={}, key={}, retryCount={}, nextRetryAt={}, err={}",
                            e.getEventId(), e.getTopic(), e.getEventKey(), e.getRetryCount(), e.getNextRetryAt(), rootMessage(ex));
                }
            }
        }

        log.info("Outbox batch done. total={}, success={}, retry={}, failed={}",
                batch.size(), success, retry, failed);
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getClass().getSimpleName() + ": " + (cur.getMessage() == null ? "" : cur.getMessage());
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
