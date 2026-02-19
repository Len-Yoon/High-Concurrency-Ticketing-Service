package com.len.ticketing.api.reservation;

import com.len.ticketing.application.ticket.TicketService;
import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final TicketService ticketService;

    public ReservationController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * 기존: Body의 queueToken만 읽었음
     * 개선: Body queueToken이 비어있으면 Header(X-QUEUE-TOKEN)를 fallback으로 사용
     *
     * 요청 예시(Body):
     * {
     *   "scheduleId": 3,
     *   "seatId": 2,
     *   "userId": 1001,
     *   "bypassQueue": false,
     *   "queueToken": "..."
     * }
     *
     * 또는 Header:
     * X-QUEUE-TOKEN: ...
     */
    @PostMapping(value = "/hold", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> hold(
            @RequestBody Map<String, Object> request,
            @RequestHeader(name = "X-QUEUE-TOKEN", required = false) String headerQueueToken
    ) {
        Long scheduleId = toLong(request.get("scheduleId"));
        Long seatId = toLong(request.get("seatId"));   // optional
        String seatNo = toNullableString(request.get("seatNo")); // optional
        Long userId = toLong(request.get("userId"));
        boolean bypassQueue = toBoolean(request.get("bypassQueue"), false);

        // Body 우선, 없으면 Header fallback
        String queueToken = toNullableString(request.get("queueToken"));
        if (isBlank(queueToken) && !isBlank(headerQueueToken)) {
            queueToken = headerQueueToken;
        }

        // (선택) Body에도 header처럼 token 이름을 다르게 보낼 수 있게 확장하고 싶으면 여기 추가 가능
        // 예: request.get("xQueueToken") 등

        TicketService.HoldResult result;

        // seatId 우선, seatNo는 backward-compatible
        if (seatId != null) {
            result = ticketService.holdSeatById(scheduleId, seatId, userId, bypassQueue, queueToken);
        } else {
            if (isBlank(seatNo)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            result = ticketService.holdSeat(scheduleId, seatNo, userId, bypassQueue, queueToken);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success", result.success());
        res.put("message", result.message());
        res.put("reservationId", result.reservationId());
        return res;
    }

    // ======================
    // helpers
    // ======================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String toNullableString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean toBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return defaultValue;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
