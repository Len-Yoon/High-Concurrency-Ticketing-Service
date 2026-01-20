import http from "k6/http";
import { sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEAT_NO = (__ENV.SEAT_NO || "A-1").trim().toUpperCase();

export const options = { vus: 20, duration: "10s" };

function headers() {
    return { headers: { "Content-Type": "application/json" } };
}

function uid() {
    return 500000 + __VU;
}

function logIfBad(tag, res) {
    if (res.status >= 200 && res.status < 300) return;
    console.log(`${tag} status=${res.status} body=${String(res.body).slice(0, 500)}`);
}

export default function () {
    const body = JSON.stringify({ scheduleId: SCHEDULE_ID, seatNo: SEAT_NO, userId: uid() });

    // hold
    const h = http.post(`${BASE_URL}/api/tickets/hold`, body, headers());

    // hold 성공한 경우에만 release를 때려서 "진짜 경로" 재현
    if (h.status >= 200 && h.status < 300) {
        const r1 = http.post(`${BASE_URL}/api/tickets/release`, body, headers());
        logIfBad("release1", r1);

        const r2 = http.post(`${BASE_URL}/api/tickets/release`, body, headers());
        logIfBad("release2", r2);
    } else {
        // 참고용: hold 실패 원인은 찍되 너무 많이 나오면 주석처리
        logIfBad("hold", h);
    }

    sleep(0.05);
}
