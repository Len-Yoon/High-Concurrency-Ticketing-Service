package com.len.ticketing.api.ticket;

import com.len.ticketing.application.ticket.TicketService;
import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final TicketService ticketService;

    @PostMapping("/hold")
    public Map<String, Object> hold(@RequestBody Map<String, Object> request) {
        Long scheduleId = toLong(request.get("scheduleId"));
        Long seatId = toNullableLong(request.get("seatId"));
        String seatNo = toNullableString(request.get("seatNo"));
        Long userId = toLong(request.get("userId"));
        boolean bypassQueue = toBoolean(request.get("bypassQueue"), false);
        String queueToken = toNullableString(request.get("queueToken"));

        if (scheduleId == null || userId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        TicketService.HoldResult result;
        if (seatId != null) {
            result = ticketService.holdSeatById(scheduleId, seatId, userId, bypassQueue, queueToken);
        } else {
            if (seatNo == null || seatNo.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            result = ticketService.holdSeat(scheduleId, seatNo, userId, bypassQueue, queueToken);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("message", result.message());
        body.put("reservationId", result.reservationId());
        return body;
    }

    @PostMapping("/release")
    public Map<String, Object> release(@RequestBody Map<String, Object> request) {
        Long scheduleId = toLong(request.get("scheduleId"));
        String seatNo = toNullableString(request.get("seatNo"));
        Long userId = toLong(request.get("userId"));

        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        ticketService.releaseSeat(scheduleId, seatNo, userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "좌석 선점이 해제되었습니다.");
        return body;
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody Map<String, Object> request) {
        Long scheduleId = toLong(request.get("scheduleId"));
        String seatNo = toNullableString(request.get("seatNo"));
        Long userId = toLong(request.get("userId"));

        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        ticketService.confirmSeat(scheduleId, seatNo, userId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "예매가 확정되었습니다.");
        return body;
    }

    // ---------- helpers ----------
    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isBlank()) return null;
            return Long.parseLong(s);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private Long toNullableLong(Object v) {
        return toLong(v);
    }

    private String toNullableString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }

    private boolean toBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "y".equals(s) || "yes".equals(s);
    }
}
