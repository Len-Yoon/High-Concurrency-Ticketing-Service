package com.len.ticketing.application.confirm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.len.ticketing.domain.outbox.OutboxEvent;
import com.len.ticketing.infra.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConfirmCommandService {

    public static final String TOPIC = "ticket.confirm.requested.v1";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void requestConfirm(Long scheduleId, String seatNo, Long userId) {
        String sn = seatNo.trim().toUpperCase();
        String eventId = UUID.randomUUID().toString();
        String key = scheduleId + ":" + sn;

        ConfirmRequestedPayload payload = new ConfirmRequestedPayload(
                eventId, scheduleId, sn, userId, LocalDateTime.now()
        );

        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxEventRepository.save(
                    OutboxEvent.pending(eventId, TOPIC, key, json)
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox payload serialize failed", e);
        }
    }
}
