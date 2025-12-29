package com.len.ticketing.infra.reservation;

import com.len.ticketing.domain.reservation.Reservation;
import com.len.ticketing.domain.reservation.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationJpaRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByUserId(Long userId);

    // 좌석 점유 row(락)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select r from Reservation r
        where r.scheduleId = :scheduleId
          and r.seatNo = :seatNo
          and r.active = 1
    """)
    Optional<Reservation> findActiveForUpdate(@Param("scheduleId") Long scheduleId,
                                              @Param("seatNo") String seatNo);

    // 좌석 상태조회용: 지금 시점 기준으로 점유중인 좌석번호만
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

    // 만료 일괄 처리
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
     * ✅ 만료를 한 번에 너무 많이 처리하면(특히 부하 테스트) 인덱스 락이 커져서 insert와 데드락이 쉽게 난다.
     * MySQL 한정으로 batch update를 제공한다.
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
}
