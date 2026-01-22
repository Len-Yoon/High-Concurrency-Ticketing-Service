import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEAT_NO = (__ENV.SEAT_NO || "A-1").trim().toUpperCase();
const DURATION = __ENV.DURATION || "20s";

export const options = {
    vus: Number(__ENV.VUS || "50"),
    duration: DURATION,
    thresholds: {
        "http_500": ["count==0"],
        "hold_latency": ["p(95)<300"],
        "release_latency": ["p(95)<300"],
    },
};

const http_500 = new Counter("http_500");
const hold_latency = new Trend("hold_latency", true);
const release_latency = new Trend("release_latency", true);
const hold_ok = new Counter("hold_ok");
const hold_409 = new Counter("hold_409");
const release_ok = new Counter("release_ok");
const release_bad = new Counter("release_bad");

function headers() {
    return { headers: { "Content-Type": "application/json" } };
}

function uid() {
    return 700000 + __VU;
}

export default function () {
    const body = JSON.stringify({ scheduleId: SCHEDULE_ID, seatNo: SEAT_NO, userId: uid() });

    // hold
    let t0 = Date.now();
    const h = http.post(`${BASE_URL}/api/tickets/hold`, body, headers());
    hold_latency.add(Date.now() - t0);

    if (h.status >= 500) http_500.add(1);

    // hold 성공한 경우에만 release
    if (h.status >= 200 && h.status < 300) {
        hold_ok.add(1);

        // release #1
        t0 = Date.now();
        const r1 = http.post(`${BASE_URL}/api/tickets/release`, body, headers());
        release_latency.add(Date.now() - t0);
        if (r1.status >= 500) http_500.add(1);

        // release #2 (멱등)
        t0 = Date.now();
        const r2 = http.post(`${BASE_URL}/api/tickets/release`, body, headers());
        release_latency.add(Date.now() - t0);
        if (r2.status >= 500) http_500.add(1);

        // release는 정책상 2xx/404/409 등 다양할 수 있음 -> 일단 5xx만 실패로 잡는다
        if (r1.status >= 200 && r1.status < 300 && r2.status < 500) release_ok.add(1);
        else release_bad.add(1);

    } else if (h.status === 409) {
        hold_409.add(1);
    }

    sleep(0.01);
}
