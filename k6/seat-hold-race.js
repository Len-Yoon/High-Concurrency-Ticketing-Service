import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 30,         // 30명이
    iterations: 30,  // 각자 1번씩만 시도 (총 30번 요청)
};

export default function () {
    const userId = Math.floor(Math.random() * 1_000_000);

    // 1. 대기열 진입 (형식 맞추기용)
    const enterPayload = JSON.stringify({
        scheduleId: 1,
        userId: userId,
    });

    http.post('http://localhost:8080/api/queue/enter', enterPayload, {
        headers: { 'Content-Type': 'application/json' },
    });

    // 2. 같은 좌석 A1을 동시에 선점 시도
    const holdPayload = JSON.stringify({
        scheduleId: 1,
        seatNo: 'A1',
        userId: userId,
    });

    const res = http.post('http://localhost:8080/api/tickets/hold', holdPayload, {
        headers: { 'Content-Type': 'application/json' },
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
    });

    // console.log(`user=${userId}, status=${res.status}, body=${res.body}`);

    sleep(0.1);
}
