package com.len.ticketing.application.reservation;

import com.len.ticketing.infra.reservation.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Profile("!loadtest")
public class ReservationExpireJob {

    private final ReservationJpaRepository reservationRepository;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void expireHolds() {
        // 너무 큰 UPDATE는 데드락 유발 가능성이 높아서 batch 처리
        reservationRepository.expireBatch(LocalDateTime.now());
    }
}
