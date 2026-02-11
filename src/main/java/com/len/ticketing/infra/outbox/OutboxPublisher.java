package com.len.ticketing.infra.outbox;

import com.len.ticketing.domain.outbox.OutboxEvent;
import com.len.ticketing.domain.outbox.OutboxStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${ticketing.outbox.batch-size:100}")
    private int batchSize;

    @Value("${ticketing.outbox.publish-timeout-ms:3000}")
    private long publishTimeoutMs;

    @Scheduled(fixedDelayString = "${ticketing.outbox.publish-interval-ms:300}")
    @Transactional
    public void publish() {
        meterRegistry.counter("ticketing.outbox.publish.tick").increment();

        final long startNs = System.nanoTime();

        int success = 0;
        int retry = 0;
        int failed = 0;

        try {
            List<OutboxEvent> batch = outboxEventRepository.lockPendingBatch(batchSize);

            if (batch == null || batch.isEmpty()) {
                meterRegistry.counter("ticketing.outbox.batch", "result", "empty").increment();
                return;
            }

            meterRegistry.summary("ticketing.outbox.batch.size").record(batch.size());

            for (OutboxEvent e : batch) {
                try {
                    kafkaTemplate
                            .send(e.getTopic(), e.getEventKey(), e.getPayload())
                            .get(publishTimeoutMs, TimeUnit.MILLISECONDS);

                    e.markPublished();
                    success++;

                } catch (Exception ex) {
                    String err = ex.getMessage();
                    if (err != null && err.length() > 500) {
                        err = err.substring(0, 500);
                    }

                    // 기존 도메인 로직 사용 (retry 증가 또는 FAILED 전환)
                    e.markRetryOrFail(err);

                    if (e.getStatus() == OutboxStatus.FAILED) {
                        failed++;
                        log.error("Outbox publish failed permanently. eventId={}, topic={}, key={}, retryCount={}, err={}",
                                e.getEventId(), e.getTopic(), e.getEventKey(), e.getRetryCount(), err);
                    } else {
                        retry++;
                        log.warn("Outbox publish retry scheduled. eventId={}, topic={}, key={}, retryCount={}, nextRetryAt={}, err={}",
                                e.getEventId(), e.getTopic(), e.getEventKey(), e.getRetryCount(), e.getNextRetryAt(), err);
                    }
                }
            }

            outboxEventRepository.saveAll(batch);

            // --- Metrics ---
            if (success > 0) {
                meterRegistry.counter("ticketing.outbox.events", "result", "published")
                        .increment((double) success);
            }
            if (retry > 0) {
                meterRegistry.counter("ticketing.outbox.events", "result", "retry")
                        .increment((double) retry);
            }
            if (failed > 0) {
                meterRegistry.counter("ticketing.outbox.events", "result", "failed")
                        .increment((double) failed);
            }

            log.info("Outbox batch done. total={}, success={}, retry={}, failed={}",
                    (success + retry + failed), success, retry, failed);

        } finally {
            meterRegistry.timer("ticketing.outbox.publish.loop")
                    .record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }
}
