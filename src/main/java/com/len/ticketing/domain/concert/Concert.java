package com.len.ticketing.domain.concert;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "concert")
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime created_at;

    private Concert(String title, String description) {
        this.title = title;
        this.description = description;
        this.created_at = LocalDateTime.now();
    }

    public static Concert create(String title, String description) {
        return new Concert(title, description);
    }
}
