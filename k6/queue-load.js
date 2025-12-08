import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 50,         // 동시에 접속하는 가상 유저 수
    duration: '30s', // 30초 동안 테스트
};

export default function () {
    const userId = Math.floor(Math.random() * 1_000_000); // 랜덤 유저 ID

    const payload = JSON.stringify({
        scheduleId: 1,
        userId: userId,
    });

    const res = http.post('http://localhost:8080/api/queue/enter', payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
    });

    // 너무 미친 속도로만 쏘지 말고 살짝 쉬었다 다음 요청
    sleep(0.5);
}
