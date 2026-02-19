package com.len.ticketing.infra.reservation;

import com.len.ticketing.domain.reservation.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationJpaRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByUserId(Long userId);

    // =========================
    // 기존: 좌석 점유 row(락) - 남겨둠(다른 곳에서 쓸 수도 있으니)
    // ⚠️ Payment.ready에서 이걸 쓰면 active row가 2개 이상일 때 500 터질 수 있음
    // =========================
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select r from Reservation r
        where r.scheduleId = :scheduleId
          and r.seatNo = :seatNo
          and r.active = 1
    """)
    Optional<Reservation> findActiveForUpdate(@Param("scheduleId") Long scheduleId,
                                              @Param("seatNo") String seatNo);

    // =========================
    // Payment ready용: "해당 사용자"의 유효 HOLD(HELD, active=1, 만료 전)만 1건 가져오기
    // - native + ORDER BY + LIMIT 1(+FOR UPDATE)로 단건을 "강제"해서 500을 영구 차단
    // =========================
    @Query(value = """
    SELECT *
      FROM reservation
     WHERE user_id     = :userId
       AND schedule_id = :scheduleId
       AND seat_no     = :seatNo
       AND active      = 1
       AND status      = 'HELD'
       AND (expires_at IS NULL OR expires_at > :now)
     ORDER BY id DESC
     LIMIT 1
     FOR UPDATE
    """, nativeQuery = true)
    Optional<Reservation> findLatestValidHoldForUpdate(@Param("userId") Long userId,
                                                       @Param("scheduleId") Long scheduleId,
                                                       @Param("seatNo") String seatNo,
                                                       @Param("now") LocalDateTime now);

    // =========================
    // 좌석 상태조회용: 점유중인 좌석번호만(HELD는 미만료만)
    // =========================
    @Query("""
        select r.seatNo from Reservation r
        where r.scheduleId = :scheduleId
          and r.active = 1
          and (
                r.status = com.len.ticketing.domain.reservation.ReservationStatus.CONFIRMED
             or (r.status = com.len.ticketing.domain.reservation.ReservationStatus.HELD and r.expiresAt > :now)
          )
    """)
    List<String> findActiveSeatNos(@Param("scheduleId") Long scheduleId,
                                   @Param("now") LocalDateTime now);

    // =========================
    // 만료 일괄 처리(JPQL)
    // =========================
    @Modifying
    @Query("""
    update Reservation r
       set r.status = com.len.ticketing.domain.reservation.ReservationStatus.EXPIRED,
           r.active = 0,
           r.updatedAt = :now
     where r.status = com.len.ticketing.domain.reservation.ReservationStatus.HELD
       and r.active = 1
       and r.expiresAt < :now
""")
    int expireAll(@Param("now") LocalDateTime now);

    /**
     * MySQL batch 만료 처리(부하 시 데드락/락경합 줄이기)
     */
    @Modifying
    @Query(value = """
        UPDATE reservation
           SET status = 'EXPIRED',
               active = 0,
               updated_at = :now
         WHERE status = 'HELD'
           AND active = 1
           AND expires_at < :now
         ORDER BY expires_at
         LIMIT 1000
        """, nativeQuery = true)
    int expireBatch(@Param("now") LocalDateTime now);

    // ===== 추가: active row 가볍게 조회 (native) =====
    interface ActiveLite {
        Long getId();
        Long getUserId();
        String getStatus();
        LocalDateTime getExpiresAt();
    }

    @Query(value = """
    SELECT id         AS id,
           user_id    AS userId,
           status     AS status,
           expires_at AS expiresAt
      FROM reservation
     WHERE schedule_id = :scheduleId
       AND seat_no = :seatNo
       AND active = 1
     ORDER BY id DESC
     LIMIT 1
    """, nativeQuery = true)
    ActiveLite findActiveLite(@Param("scheduleId") Long scheduleId,
                              @Param("seatNo") String seatNo);

    // ===== (선택이지만 강추) 중복 HELD 정리: keepId 제외하고 나머지는 EXPIRED 처리 =====
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
    UPDATE reservation
       SET status = 'EXPIRED',
           active = 0,
           updated_at = :now
     WHERE user_id     = :userId
       AND schedule_id = :scheduleId
       AND seat_no     = :seatNo
       AND active      = 1
       AND status      = 'HELD'
       AND id <> :keepId
    """, nativeQuery = true)
    int expireOtherActiveHolds(@Param("userId") Long userId,
                               @Param("scheduleId") Long scheduleId,
                               @Param("seatNo") String seatNo,
                               @Param("keepId") Long keepId,
                               @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
    UPDATE reservation
       SET status = 'EXPIRED',
           active = 0,
           updated_at = :now
     WHERE schedule_id = :scheduleId
       AND seat_no = :seatNo
       AND active = 1
       AND status = 'HELD'
       AND expires_at <= :now
    """, nativeQuery = true)
    int expireIfExpired(@Param("scheduleId") Long scheduleId,
                        @Param("seatNo") String seatNo,
                        @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
    UPDATE reservation
       SET status = 'CANCELLED',
           active = 0,
           expires_at = NULL,
           updated_at = :now
     WHERE user_id = :userId
       AND schedule_id = :scheduleId
       AND seat_no = :seatNo
       AND active = 1
       AND status = 'HELD'
    """, nativeQuery = true)
    int cancelHold(@Param("userId") Long userId,
                   @Param("scheduleId") Long scheduleId,
                   @Param("seatNo") String seatNo,
                   @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
    UPDATE reservation
       SET status = 'CONFIRMED',
           updated_at = :now
     WHERE user_id = :userId
       AND schedule_id = :scheduleId
       AND seat_no = :seatNo
       AND active = 1
       AND status = 'HELD'
       AND (expires_at IS NULL OR expires_at > :now)
    """, nativeQuery = true)
    int confirmHold(@Param("userId") Long userId,
                    @Param("scheduleId") Long scheduleId,
                    @Param("seatNo") String seatNo,
                    @Param("now") LocalDateTime now);

    @Query(value = """
    SELECT COUNT(1)
    FROM reservation r
    WHERE r.user_id = :userId
        AND r.schedule_id = :scheduleId
        AND r.seat_no = :seatNo
        AND r.status = 'HELD'
        AND r.active = 1
        AND r.expires_at > :now
    """, nativeQuery = true)
    int countValidHold(@Param("userId") Long userId,
                       @Param("scheduleId") Long scheduleId,
                       @Param("seatNo") String seatNo,
                       @Param("now") LocalDateTime now);
}
