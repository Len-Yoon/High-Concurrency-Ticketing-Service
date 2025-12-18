package com.len.ticketing.application.reservation;

import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ReservationExpireJob {

    private final ReservationJpaRepository reservationRepository;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void expireHolds() {
        reservationRepository.expireAll(LocalDateTime.now());
    }
}
