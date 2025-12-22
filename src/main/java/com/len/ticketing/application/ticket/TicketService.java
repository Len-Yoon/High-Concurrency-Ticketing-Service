package com.len.ticketing.application.ticket;

import com.len.ticketing.common.exception.BusinessException;
import com.len.ticketing.common.exception.ErrorCode;
import com.len.ticketing.domain.queue.QueueStore;
import com.len.ticketing.application.reservation.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class TicketService {

    // 대기열 상위 몇 명까지 입장 허용할지
    private static final long ALLOWED_QUEUE_RANK = 100L;

    private final QueueStore queueStore;
    private final ReservationService reservationService;

    /**
     * 좌석 선점(HOLD) - 이제 Redis락이 아니라 DB reservation(HELD/active=1)로 선점
     */
    @Transactional
    public HoldSeatResult holdSeat(long scheduleId, String seatNo, long userId) {

        // 0) 대기열 체크
        boolean canEnter = queueStore.canEnter(scheduleId, userId, ALLOWED_QUEUE_RANK);
        if (!canEnter) {
            return new HoldSeatResult(false, "대기열 순번이 아직 입장 가능 범위가 아닙니다.");
        }

        // 1) DB 기반 HOLD 시도
        try {
            reservationService.hold(userId, scheduleId, seatNo);
            return new HoldSeatResult(true, "좌석 선점에 성공했습니다. 결제를 진행해주세요.");
        } catch (BusinessException e) {
            // seat not found 같은 케이스
            throw e;
        } catch (IllegalStateException e) {
            // 이미 홀드/예매된 좌석 등
            // ErrorCode가 있으면 거기에 맞춰 BusinessException으로 바꾸는게 베스트인데,
            // 일단 메시지로 반환
            return new HoldSeatResult(false, e.getMessage());
        }
    }

    /**
     * 선점 해제는 "reservation 취소/만료"로 처리해야 함.
     * 지금은 cancel API를 별도로 만들기 전이므로, 일단 미지원으로 둔다.
     */
    @Transactional
    public void releaseSeat(long scheduleId, String seatNo, long userId) {
        // TODO: reservationService.cancel(userId, scheduleId, seatNo) 같은 메서드 만들면 여기서 호출
        throw new UnsupportedOperationException("releaseSeat는 reservation cancel로 구현해야 합니다.");
    }

    public record HoldSeatResult(boolean success, String message) {}
}
