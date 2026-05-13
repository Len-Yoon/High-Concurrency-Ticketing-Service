-- Minimal schema init for tables not managed by JPA
-- MySQL 8.x

CREATE TABLE IF NOT EXISTS confirmed_seat_guard (
    chedule_id     BIGINT       NOT NULL,
    seat_no         VARCHAR(32)  NOT NULL,
    reservation_id  BIGINT       NOT NULL,
    confirmed_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (schedule_id, seat_no)
    ) ENGINE=InnoDB;


CREATE TABLE IF NOT EXISTS consumer_dedup (
  event_id     VARCHAR(64) NOT NULL,
  processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;