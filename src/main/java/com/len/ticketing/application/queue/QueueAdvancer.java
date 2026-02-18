package com.len.ticketing.application.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueAdvancer {

    @Scheduled(fixedDelayString = "${ticketing.queue.advance-interval-ms:200}")
    public void advance() {
        // TODO: Redis Lua 호출로 advance 수행
        log.debug("queue advance tick");
    }
}
