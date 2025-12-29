package com.len.ticketing.application.ticket;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.application.reservation.ReservationService;
import com.len.ticketing.domain.queue.QueueStore;
import com.len.ticketing.domain.ticket.SeatLockStore;
import com.len.ticketing.infra.concert.SeatJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RequiredArgsConstructor
@Service
public class TicketService {

    private static final long SEAT_LOCK_TTL_SECONDS = 300L; // 5분
    private static final long ALLOWED_QUEUE_RANK = 100L;

    private final SeatJpaRepository seatRepository;
    private final QueueStore queueStore;
    private final SeatLockStore seatLockStore;
    private final ReservationService reservationService;

    /**
     * ✅ 여기서는 트랜잭션을 잡지 않는다.
     * - Redis/대기열/좌석 존재 체크는 트랜잭션 필요 없음
     * - DB hold는 ReservationService 쪽에서 짧게 트랜잭션으로 처리
     * - 데드락/락 획득 실패는 짧게 재시도(새 트랜잭션)해서 5xx를 줄인다.
     */
    public HoldSeatResult holdSeat(Long scheduleId, String seatNo, Long userId) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String sn = seatNo.trim().toUpperCase();

        // 대기열을 먼저 탄 적 없으면 자동 진입시켜서 스킵을 막는다.
        if (queueStore.getPosition(scheduleId, userId) == -1L) {
            queueStore.enterQueue(scheduleId, userId);
        }

        boolean canEnter = queueStore.canEnter(scheduleId, userId, ALLOWED_QUEUE_RANK);
        if (!canEnter) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ALLOWED);
        }

        boolean seatExists = seatRepository.existsBySchedule_IdAndSeatNo(scheduleId, sn);
        if (!seatExists) {
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        }

        boolean locked = seatLockStore.lockSeat(scheduleId, sn, userId, SEAT_LOCK_TTL_SECONDS);
        if (!locked) {
            Long owner = seatLockStore.getLockOwner(scheduleId, sn);
            if (owner != null && !owner.equals(userId)) {
                throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
            }
            return new HoldSeatResult(false, "좌석 선점에 실패했습니다.");
        }

        // Redis 락을 잡은 상태에서 DB에 hold row를 만든다.
        // ✅ MySQL 데드락은 부하 상황에서 종종 발생 -> 짧게 재시도하면 대부분 해결된다.
        int maxAttempts = 5;
        long backoffMs = 10;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                reservationService.hold(userId, scheduleId, sn);
                return new HoldSeatResult(true, "좌석 선점에 성공했습니다. 결제를 진행해주세요.");
            } catch (CannotAcquireLockException | DeadlockLoserDataAccessException e) {
                if (attempt == maxAttempts) {
                    // 여기까지 오면 진짜 서버가 밀리는 상태 -> 락 풀고 500
                    seatLockStore.releaseSeat(scheduleId, sn, userId);
                    throw e;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                backoffMs = Math.min(backoffMs * 2, 100);
            } catch (RuntimeException e) {
                // 기타 예외는 재시도 의미 없음 -> 락 풀고 종료
                seatLockStore.releaseSeat(scheduleId, sn, userId);
                throw e;
            }
        }
        // 이 라인에 도달하지 않음
        seatLockStore.releaseSeat(scheduleId, sn, userId);
        return new HoldSeatResult(false, "좌석 선점에 실패했습니다.");
    }

    // ✅ TicketController가 찾는 시그니처( Long, String, Long ) 맞춰서 추가
    @Transactional
    public void releaseSeat(Long scheduleId, String seatNo, Long userId) {
        if (scheduleId == null || userId == null || seatNo == null || seatNo.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String sn = seatNo.trim().toUpperCase();

        // ✅ (중요) DB 커밋되기 전에 Redis 락을 먼저 풀면
        // 다른 사용자가 락을 잡고 DB에 들어오면서(hold) cancelHold 트랜잭션과 충돌/데드락이 급증할 수 있다.
        // -> DB 취소를 먼저 하고, 커밋 이후(afterCommit)에 Redis 락을 해제한다.
        try {
            reservationService.cancelHold(userId, scheduleId, sn);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.HOLD_NOT_FOUND) throw e;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                seatLockStore.releaseSeat(scheduleId, sn, userId);
            }
        });
    }

    public record HoldSeatResult(boolean success, String message) {}
}
