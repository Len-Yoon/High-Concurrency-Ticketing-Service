package com.len.ticketing.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.len.ticketing.application.confirm.ConfirmRequestedPayload;
import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.application.ticket.TicketService;
import com.len.ticketing.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final TicketService ticketService;
    private final JdbcTemplate jdbcTemplate;
    private final ReservationService reservationService;

    /**
     * 0 이하로 주면 stale 체크 비활성화
     */
    @Value("${ticketing.confirm.max-event-age-seconds:120}")
    private long maxEventAgeSeconds;

    @KafkaListener(topics = "ticket.confirm.requested.v1", groupId = "ticketing-confirm-v1")
    public void onMessage(String payload) {
        ConfirmRequestedPayload evt;

        // 1) 파싱 실패 -> skip
        try {
            evt = objectMapper.readValue(payload, ConfirmRequestedPayload.class);
        } catch (Exception e) {
            log.warn("Skip invalid payload. payload={}", payload, e);
            return;
        }

        // 2) 필수 필드 검증
        if (evt.eventId() == null || evt.eventId().isBlank()
                || evt.scheduleId() == null
                || evt.userId() == null
                || evt.seatNo() == null || evt.seatNo().isBlank()) {
            log.warn("Skip invalid event fields. event={}", evt);
            return;
        }

        final String seatNo = evt.seatNo().trim().toUpperCase();

        // 3) consumer 멱등 (이미 처리한 event면 skip)
        int inserted = jdbcTemplate.update(
                "INSERT IGNORE INTO consumer_dedup(event_id, processed_at) VALUES (?, NOW())",
                evt.eventId()
        );
        if (inserted == 0) {
            log.debug("Duplicate event skipped. eventId={}", evt.eventId());
            return;
        }

        // 4) stale 이벤트 skip (requestedAt + max age 기준)
        if (isStale(evt.requestedAt())) {
            log.info("Skip stale confirm. eventId={}, requestedAt={}, maxAgeSec={}",
                    evt.eventId(), evt.requestedAt(), maxEventAgeSeconds);
            return;
        }

        // 5) 유효 HOLD pre-check (없으면 skip)
        boolean validHold = reservationService.hasValidHold(
                evt.userId(),
                evt.scheduleId(),
                seatNo,
                LocalDateTime.now()
        );

        if (!validHold) {
            log.warn("Skip invalid hold state. eventId={}, scheduleId={}, seatNo={}, userId={}",
                    evt.eventId(), evt.scheduleId(), seatNo, evt.userId());
            return;
        }

        // 6) 실제 확정 처리
        try {
            ticketService.confirmSeat(evt.scheduleId(), seatNo, evt.userId());
            log.info("Confirm processed. eventId={}, scheduleId={}, seatNo={}, userId={}",
                    evt.eventId(), evt.scheduleId(), seatNo, evt.userId());
        } catch (BusinessException e) {
            // 비즈니스 예외는 재시도해도 바뀌지 않는 경우가 많아 skip
            log.warn("Skip non-retryable business error. eventId={}, code={}",
                    evt.eventId(), e.getErrorCode());
        }
    }

    private boolean isStale(Instant requestedAt) {
        if (requestedAt == null) return false;
        if (maxEventAgeSeconds <= 0) return false; // stale 체크 비활성화
        Instant cutoff = Instant.now().minusSeconds(maxEventAgeSeconds);
        return requestedAt.isBefore(cutoff);
    }
}
