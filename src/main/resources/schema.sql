-- Minimal schema init for tables not managed by JPA
-- MySQL 8.x

CREATE TABLE IF NOT EXISTS confirmed_seat_guard (
                                                    schedule_id     BIGINT       NOT NULL,
                                                    seat_no         VARCHAR(32)  NOT NULL,
    reservation_id  BIGINT       NOT NULL,
    confirmed_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (schedule_id, seat_no)
    ) ENGINE=InnoDB;