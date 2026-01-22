import http from "k6/http";
import { sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEATS_SAMPLE = Number(__ENV.SEATS_SAMPLE || "50"); // A-1 ~ A-50

export const options = { vus: 20, duration: "10s" };

function headers() {
    return { headers: { "Content-Type": "application/json" } };
}

function uid() {
    return 600000 + __VU;
}

function seatNoForThisIter() {
    // VU/ITER 기반으로 좌석 분산: A-1..A-50
    const n = ((__VU - 1) % SEATS_SAMPLE) + 1;
    return `A-${n}`;
}

function logIfBad(tag, res) {
    if (res.status >= 200 && res.status < 300) return;
    console.log(`${tag} status=${res.status} body=${String(res.body).slice(0, 700)}`);
}

export default function () {
    const seatNo = seatNoForThisIter();
    const body = JSON.stringify({ scheduleId: SCHEDULE_ID, seatNo, userId: uid() });

    const h = http.post(`${BASE_URL}/api/tickets/hold`, body, headers());

    if (h.status >= 200 && h.status < 300) {
        // hold 성공한 놈만 release 2번(멱등)
        const r1 = http.post(`${BASE_URL}/api/tickets/release`, body, headers());
        logIfBad("release1", r1);

        const r2 = http.post(`${BASE_URL}/api/tickets/release`, body, headers());
        logIfBad("release2", r2);
    } else {
        // 필요하면 hold 실패도 찍어보되, 너무 많이 나오면 주석처리
        // logIfBad("hold", h);
    }

    sleep(0.05);
}
