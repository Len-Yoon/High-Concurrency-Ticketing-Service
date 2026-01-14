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
     * ✅ MySQL batch 만료 처리(부하 시 데드락/락경합 줄이기)
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

    // ============================================================
    // ✅ 추가(핵심): "유니크 충돌 처리"를 위해 active row를 가볍게 조회
    // ============================================================
    interface ActiveLite {
        Long getId();
        Long getUserId();
        String getStatus();          // DB enum('HELD','CONFIRMED'...) 그대로 문자열로 받는게 안전
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
         LIMIT 1
        """, nativeQuery = true)
    ActiveLite findActiveLite(@Param("scheduleId") Long scheduleId,
                              @Param("seatNo") String seatNo);

    // ============================================================
    // ✅ 추가(핵심): 만료된 HELD를 active=0으로 내려 유니크(active_uk) 해제
    // - hold 충돌(DataIntegrityViolation) 났을 때 "만료면 정리하고 1회 재시도"에 쓰려고 추가
    // ============================================================
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE reservation
           SET status = 'EXPIRED',
               active = 0,
               updated_at = NOW(6)
         WHERE schedule_id = :scheduleId
           AND seat_no = :seatNo
           AND active = 1
           AND status = 'HELD'
           AND expires_at < :now
        """, nativeQuery = true)
    int expireIfExpired(@Param("scheduleId") Long scheduleId,
                        @Param("seatNo") String seatNo,
                        @Param("now") LocalDateTime now);

    // ============================================================
    // ✅ 추가: release(취소)용 - INSERT 절대 금지, UPDATE만
    // ============================================================
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE reservation
           SET active = 0,
               status = 'CANCELLED',
               expires_at = NULL,
               updated_at = NOW(6)
         WHERE user_id = :userId
           AND schedule_id = :scheduleId
           AND seat_no = :seatNo
           AND active = 1
           AND status = 'HELD'
        """, nativeQuery = true)
    int cancelHold(@Param("userId") Long userId,
                   @Param("scheduleId") Long scheduleId,
                   @Param("seatNo") String seatNo);

    // (선택) 멱등 처리용: 누가 active를 잡고 있는지
    @Query(value = """
        SELECT user_id
          FROM reservation
         WHERE schedule_id = :scheduleId
           AND seat_no = :seatNo
           AND active = 1
         LIMIT 1
        """, nativeQuery = true)
    Long findActiveOwner(@Param("scheduleId") Long scheduleId,
                         @Param("seatNo") String seatNo);
}
