import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// ===== 옵션 =====
export const options = {
    vus: 100,
    duration: '30s',
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || '1');
const SEAT_START = Number(__ENV.SEAT_START || '1');
const SEAT_END = Number(__ENV.SEAT_END || '50');

// 메트릭
const holdDuration = new Trend('hold_duration');
const successReservations = new Counter('success_reservations');
const alreadyReserved = new Counter('already_reserved');
const unexpectedErrors = new Counter('unexpected_errors');

function randomSeatNo() {
    const n = Math.floor(Math.random() * (SEAT_END - SEAT_START + 1)) + SEAT_START;
    return `A-${n}`;
}

export default function () {
    const payload = JSON.stringify({
        userId: 1,
        scheduleId: SCHEDULE_ID,
        seatNo: randomSeatNo(),
    });

    const params = {
        headers: { 'Content-Type': 'application/json' },
    };

    const res = http.post(`${BASE_URL}/api/reservations/hold`, payload, params);

    holdDuration.add(res.timings.duration);

    if (res.status >= 200 && res.status < 300) {
        successReservations.add(1);
    } else if (res.status === 409) {
        alreadyReserved.add(1);
    } else {
        unexpectedErrors.add(1);
        console.error(`UNEXPECTED status=${res.status}, body=${res.body}`);
    }

    check(res, {
        'status is expected (2xx/409)': (r) =>
            (r.status >= 200 && r.status < 300) || r.status === 409,
    });

    sleep(1);
}
