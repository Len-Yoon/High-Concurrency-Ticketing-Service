package com.len.ticketing.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.len.ticketing.application.confirm.ConfirmRequestedPayload;
import com.len.ticketing.application.ticket.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConfirmRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final TicketService ticketService;
    private final JdbcTemplate jdbcTemplate;

    @KafkaListener(topics = "ticket.confirm.requested.v1", groupId = "ticketing-confirm-v1")
    @Transactional
    public void onMessage(String payload) throws Exception {
        ConfirmRequestedPayload evt = objectMapper.readValue(payload, ConfirmRequestedPayload.class);

        // dedup (MySQL)
        int inserted = jdbcTemplate.update(
                "INSERT IGNORE INTO consumer_dedup(event_id, processed_at) VALUES (?, NOW())",
                evt.eventId()
        );
        if (inserted == 0) return; // 이미 처리됨

        ticketService.confirmSeat(evt.scheduleId(), evt.seatNo(), evt.userId());
    }
}
