package com.len.ticketing.infra.outbox;

import com.len.ticketing.domain.outbox.OutboxEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${ticketing.outbox.publish-interval-ms:300}")
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> batch = outboxEventRepository.lockPendingBatch(100);

        for (OutboxEvent e : batch) {
            try {
                kafkaTemplate.send(e.getTopic(), e.getEventKey(), e.getPayload()).get();
                e.markPublished();
            } catch (Exception ex) {
                e.markRetryOrFail(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }
}
