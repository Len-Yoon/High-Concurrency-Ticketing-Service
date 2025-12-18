package com.len.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class HighConcurrencyTicketingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HighConcurrencyTicketingServiceApplication.class, args);
    }

}
