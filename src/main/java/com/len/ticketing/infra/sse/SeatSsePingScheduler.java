package com.len.ticketing.infra.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class SeatSsePingScheduler {

    private final SeatSseHub hub;

    @Scheduled(fixedRate = 15000) // 15초마다
    public void ping() {
        hub.pingAll();
    }
}
