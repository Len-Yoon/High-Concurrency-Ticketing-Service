package com.len.ticketing.api.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.len.ticketing.application.ticket.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    TicketService ticketService;

    @Test
    @DisplayName("hold: seatId로 요청 시 성공")
    void hold_withSeatId_success() throws Exception {
        given(ticketService.holdSeatById(eq(1L), eq(1L), eq(101L), eq(true), isNull()))
                .willReturn(new TicketService.HoldSeatResult(true, "ok"));

        String body = """
                {
                  "scheduleId": 1,
                  "seatId": 1,
                  "userId": 101
                }
                """;

        mockMvc.perform(post("/api/reservations/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-LOADTEST-BYPASS", "true")
                        .content(body))
                .andExpect(status().isOk());

        verify(ticketService).holdSeatById(1L, 1L, 101L, true, null);
        verify(ticketService, never()).holdSeat(anyLong(), anyString(), anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("hold: seatNo로 요청 시 성공(하위호환)")
    void hold_withSeatNo_success() throws Exception {
        given(ticketService.holdSeat(eq(1L), eq("A1"), eq(101L), eq(true), isNull()))
                .willReturn(new TicketService.HoldSeatResult(true, "ok"));

        String body = """
                {
                  "scheduleId": 1,
                  "seatNo": "A1",
                  "userId": 101
                }
                """;

        mockMvc.perform(post("/api/reservations/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-LOADTEST-BYPASS", "true")
                        .content(body))
                .andExpect(status().isOk());

        verify(ticketService).holdSeat(1L, "A1", 101L, true, null);
        verify(ticketService, never()).holdSeatById(anyLong(), anyLong(), anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("hold: seatId/seatNo 둘 다 없으면 400")
    void hold_withoutSeat_should400() throws Exception {
        String body = """
                {
                  "scheduleId": 1,
                  "userId": 101
                }
                """;

        mockMvc.perform(post("/api/reservations/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-LOADTEST-BYPASS", "true")
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
