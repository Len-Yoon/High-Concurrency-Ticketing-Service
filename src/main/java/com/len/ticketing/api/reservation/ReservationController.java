package com.len.ticketing.api.reservation;

import com.len.ticketing.api.reservation.dto.ReservationResponse;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationJpaRepository reservationRepository;

    /**
     * 특정 사용자(userId)의 예매 목록 조회
     * GET /api/reservations?userId=101
     */
    @GetMapping
    public List<ReservationResponse> getByUser(@RequestParam Long userId) {
        return reservationRepository.findByUserId(userId).stream()
                .map(reservation -> new ReservationResponse(
                        reservation.getId(),
                        reservation.getUserId(),
                        reservation.getScheduleId(),
                        reservation.getSeatNo(),
                        reservation.getCreatedAt()
                ))
                .toList();
    }
}
