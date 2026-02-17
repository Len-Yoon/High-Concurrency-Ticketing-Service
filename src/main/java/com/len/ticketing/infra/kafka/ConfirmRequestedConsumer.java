package com.len.ticketing.infra.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.len.ticketing.application.confirm.ConfirmRequestedPayload;
import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.application.ticket.TicketService;
import com.len.ticketing.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
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

    private static final String METRIC_SKIP = "ticketing.confirm.skip";
    private static final String METRIC_PROCESSED = "ticketing.confirm.processed";
    private static final String METRIC_RETRYABLE_ERROR = "ticketing.confirm.retryable_error";

    private final ObjectMapper objectMapper;
    private final TicketService ticketService;
    private final JdbcTemplate jdbcTemplate;
    private final ReservationService reservationService;
    private final MeterRegistry meterRegistry;

    /**
     * 0 이하이면 stale 체크 비활성화
     */
    @Value("${ticketing.confirm.max-event-age-seconds:120}")
    private long maxEventAgeSeconds;

    @KafkaListener(
            topics = "ticket.confirm.requested.v1",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(String payload) {
        ConfirmRequestedPayload evt;

        // 1) JSON 파싱
        try {
            evt = objectMapper.readValue(payload, ConfirmRequestedPayload.class);
        } catch (Exception e) {
            countSkip("invalid_payload");
            log.warn("Skip invalid payload. payload={}", payload, e);
            return;
        }

        // 2) 필수 필드 검증
        if (evt.eventId() == null || evt.eventId().isBlank()
                || evt.scheduleId() == null
                || evt.userId() == null
                || evt.seatNo() == null || evt.seatNo().isBlank()) {
            countSkip("invalid_fields");
            log.warn("Skip invalid event fields. event={}", evt);
            return;
        }

        final String seatNo = evt.seatNo().trim().toUpperCase();

        // 3) consumer 멱등 (이미 처리된 event면 skip)
        int inserted = jdbcTemplate.update(
                "INSERT IGNORE INTO consumer_dedup(event_id, processed_at) VALUES (?, NOW())",
                evt.eventId()
        );
        if (inserted == 0) {
            countSkip("duplicate");
            log.debug("Duplicate event skipped. eventId={}", evt.eventId());
            return;
        }

        // 4) stale 이벤트 skip
        if (isStale(evt.requestedAt())) {
            countSkip("stale");
            log.info("Skip stale confirm. eventId={}, requestedAt={}, maxAgeSec={}",
                    evt.eventId(), evt.requestedAt(), maxEventAgeSeconds);
            return;
        }

        // 5) 유효 HOLD pre-check
        boolean validHold = reservationService.hasValidHold(
                evt.userId(),
                evt.scheduleId(),
                seatNo,
                LocalDateTime.now()
        );

        if (!validHold) {
            countSkip("hold_not_valid");
            log.warn("Skip invalid hold state. eventId={}, scheduleId={}, seatNo={}, userId={}",
                    evt.eventId(), evt.scheduleId(), seatNo, evt.userId());
            return;
        }

        // 6) 실제 확정 처리
        try {
            ticketService.confirmSeat(evt.scheduleId(), seatNo, evt.userId());
            meterRegistry.counter(METRIC_PROCESSED).increment();
            log.info("Confirm processed. eventId={}, scheduleId={}, seatNo={}, userId={}",
                    evt.eventId(), evt.scheduleId(), seatNo, evt.userId());

        } catch (BusinessException e) {
            // 비즈니스 예외는 비재시도 성격으로 처리
            countSkip("business_exception");
            log.warn("Skip non-retryable business error. eventId={}, code={}",
                    evt.eventId(), e.getErrorCode());

        } catch (Exception e) {
            // 재시도 가능한 오류: dedup 롤백 후 예외 재던짐(재처리 가능하게)
            meterRegistry.counter(METRIC_RETRYABLE_ERROR).increment();
            rollbackDedup(evt.eventId());
            log.error("Retryable confirm error. eventId={}", evt.eventId(), e);
            throw e;
        }
    }

    private boolean isStale(Instant requestedAt) {
        if (requestedAt == null) return false;
        if (maxEventAgeSeconds <= 0) return false;
        Instant cutoff = Instant.now().minusSeconds(maxEventAgeSeconds);
        return requestedAt.isBefore(cutoff);
    }

    private void countSkip(String reason) {
        meterRegistry.counter(METRIC_SKIP, "reason", reason).increment();
    }

    private void rollbackDedup(String eventId) {
        try {
            jdbcTemplate.update("DELETE FROM consumer_dedup WHERE event_id = ?", eventId);
            log.warn("Dedup rollback done. eventId={}", eventId);
        } catch (Exception ex) {
            log.error("Dedup rollback failed. eventId={}", eventId, ex);
        }
    }
}
