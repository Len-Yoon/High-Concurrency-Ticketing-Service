package com.len.ticketing.application.confirm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.domain.outbox.OutboxEvent;
import com.len.ticketing.infra.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConfirmCommandService {

    public static final String TOPIC = "ticket.confirm.requested.v1";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ReservationService reservationService;

    @Transactional
    public void requestConfirm(Long scheduleId, String seatNo, Long userId) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CONFIRM_REQUEST");
        }

        String sn = seatNo.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();

        // 유효 HOLD 없으면 이벤트 발행 안 함
        if (!reservationService.hasValidHold(userId, scheduleId, sn, now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "HOLD_NOT_FOUND");
        }

        String eventId = UUID.randomUUID().toString();
        String key = scheduleId + ":" + sn;

        ConfirmRequestedPayload payload = new ConfirmRequestedPayload(
                eventId, scheduleId, sn, userId, Instant.now()
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
