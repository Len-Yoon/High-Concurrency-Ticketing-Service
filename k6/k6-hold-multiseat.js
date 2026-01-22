import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const VUS = Number(__ENV.VUS || "50");
const DURATION = __ENV.DURATION || "10s";
const SEATS_SAMPLE = Number(__ENV.SEATS_SAMPLE || "200"); // A-1 ~ A-200

const hold_ok = new Counter("hold_ok");
const hold_409 = new Counter("hold_409");
const hold_etc = new Counter("hold_etc");
const http_500 = new Counter("http_500");
const hold_latency = new Trend("hold_latency", true);

export const options = {
    vus: VUS,
    duration: DURATION,
    thresholds: {
        http_500: ["count==0"],
        hold_latency: ["p(95)<200"], // 멀티좌석이면 이 정도로 다시 낮춰볼만 함(필요시 조정)
    },
};

const holdParams = {
    headers: {
        "Content-Type": "application/json",
        "X-LOADTEST-BYPASS": "true",
    },
};

function seatNoForVu() {
    // VU별로 기본 좌석을 다르게(경합 줄이기)
    const n = (__VU % SEATS_SAMPLE) + 1; // 1..SEATS_SAMPLE
    return `A-${n}`;
}

function userId() {
    return 100000 + __VU;
}

export default function () {
    const seatNo = seatNoForVu();

    const body = JSON.stringify({
        scheduleId: SCHEDULE_ID,
        seatNo,
        userId: userId(),
    });

    const t0 = Date.now();
    const res = http.post(`${BASE_URL}/api/tickets/hold`, body, holdParams);
    hold_latency.add(Date.now() - t0);

    check(res, { "hold is 2xx/409": (r) => (r.status >= 200 && r.status < 300) || r.status === 409 });

    if (res.status >= 500) http_500.add(1);
    if (res.status >= 200 && res.status < 300) hold_ok.add(1);
    else if (res.status === 409) hold_409.add(1);
    else hold_etc.add(1);

    sleep(0.05);
}
