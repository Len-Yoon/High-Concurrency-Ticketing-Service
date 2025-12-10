package com.len.ticketing.api.reservation;

import com.len.ticketing.api.reservation.dto.ReservationRequest;
import com.len.ticketing.api.reservation.dto.ReservationResponse;
import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.infra.reservation.ReservationJpaRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationJpaRepository reservationRepository;
    private final ReservationService reservationService; // 서비스 하나 주입

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

    /**
     * 좌석 홀드 / 예매 생성
     * POST /api/reservations/hold
     */
    @PostMapping("/hold")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse hold(@RequestBody ReservationRequest request) {
        var reservation = reservationService.hold(
                request.userId(),
                request.scheduleId(),
                request.seatNo()
        );

        return new ReservationResponse(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getScheduleId(),
                reservation.getSeatNo(),
                reservation.getCreatedAt()
        );
    }
}
