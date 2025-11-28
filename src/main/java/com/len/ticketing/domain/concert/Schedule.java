package com.len.ticketing.domain.concert;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "schedule")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 단방향 ManyToOne
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concert_id")
    private Concert concert;

    @Column(name = "show_at", nullable = false)
    private LocalDateTime showAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private Schedule(Concert concert, LocalDateTime showAt) {
        this.concert = concert;
        this.showAt = showAt;
        this.createdAt = LocalDateTime.now();
    }

    public static Schedule create(Concert concert, LocalDateTime showAt) {
        return new Schedule(concert, showAt);
    }
}