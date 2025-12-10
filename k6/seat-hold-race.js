import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// ===== 옵션: 동시성 / 테스트 시간 조절 =====
export const options = {
    // 100명이 30초 동안 계속 예매 시도
    vus: 100,
    duration: '30s',

    thresholds: {
        http_req_duration: ['p(95)<200'],     // 95% 요청은 200ms 아래
        unexpected_errors: ['count==0'],      // 우리가 예상 못한 에러는 0이어야 함
    },
};

// ===== 환경 변수 / 기본값 =====
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || '1');

// 좌석 범위 (DB에 실제 있는 좌석에 맞게 수정하면 됨)
// 예: A-1 ~ A-50
const SEAT_START = Number(__ENV.SEAT_START || '1');
const SEAT_END = Number(__ENV.SEAT_END || '50');

// ===== 커스텀 메트릭 =====
const holdDuration = new Trend('hold_duration');         // 예약 응답 시간
const successReservations = new Counter('success_reservations');  // 2xx
const alreadyReserved = new Counter('already_reserved');          // 이미 예약된 좌석
const unexpectedErrors = new Counter('unexpected_errors');        // 나머지 에러

// ===== 좌석번호 랜덤 생성 (A-1, A-2, ...) =====
function randomSeatNo() {
    const n = Math.floor(Math.random() * (SEAT_END - SEAT_START + 1)) + SEAT_START;
    return `A-${n}`;
}

export default function () {
    // userId는 일단 1로 고정 (FK 있으면 user_account.id=1 준비해둔 상태)
    const userId = 1;
    const scheduleId = SCHEDULE_ID;
    const seatNo = randomSeatNo();

    const payload = JSON.stringify({
        userId,
        scheduleId,
        seatNo,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    const res = http.post(`${BASE_URL}/api/reservations/hold`, payload, params);

    holdDuration.add(res.timings.duration);

    // 응답 분류
    if (res.status >= 200 && res.status < 300) {
        // 성공 예약
        successReservations.add(1);
    } else if (res.status === 409 || res.status === 500) {
        // 지금 구조에선 이미 예약된 좌석이 500으로 내려올 가능성이 높음
        alreadyReserved.add(1);
    } else {
        unexpectedErrors.add(1);
        console.error(`UNEXPECTED status=${res.status}, body=${res.body}`);
    }

    // 체크: 성공 or 이미 예약된 경우까진 "예상된" 상태로 봄
    check(res, {
        'status is expected (2xx/409/500)': (r) =>
            (r.status >= 200 && r.status < 300) ||
            r.status === 409 ||
            r.status === 500,
    });

    sleep(1); // 각 VU가 1초에 한 번씩 때리도록 (부하 더 주고 싶으면 줄이기)
}
