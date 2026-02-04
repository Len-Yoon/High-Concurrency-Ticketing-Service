package com.len.ticketing.domain.queue;

public interface QueueStore {

    /** 대기열 진입 (멱등: 이미 있으면 순번 유지) */
    long enterQueue(long scheduleId, long userId);

    /** 현재 순번 조회 (없으면 -1) */
    long getPosition(long scheduleId, long userId);

    /** (기존) 랭크 컷 기준 통과 여부 */
    boolean canEnter(long scheduleId, long userId, long allowedRank);

    // ===== 실전형(전진형)용: PASS 토큰 =====

    /** pass 토큰 조회(없으면 null) */
    QueuePass getPass(long scheduleId, long userId);

    /**
     * pass 발급 시도:
     * - 만료 pass 정리
     * - user가 상위 allowedSlots 안이면(pass 슬롯 여유 있을 때) 토큰 발급
     * - 발급 시 대기열에서 제거
     */
    QueuePass tryIssuePass(long scheduleId, long userId, long allowedSlots, long passTtlSeconds);

    /** 토큰 검증 */
    boolean validatePass(long scheduleId, long userId, String token);

    /** pass 해제(결제 완료/이탈 시) */
    void releasePass(long scheduleId, long userId);
}
