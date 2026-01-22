import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";
import exec from "k6/execution";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const VUS = Number(__ENV.VUS || "50");
const DURATION = __ENV.DURATION || "20s";
const SEATS_SAMPLE = Number(__ENV.SEATS_SAMPLE || "200"); // A-1 ~ A-200

const hold_ok = new Counter("hold_ok");
const hold_409 = new Counter("hold_409");
const hold_etc = new Counter("hold_etc");
const release_ok = new Counter("release_ok");
const release_bad = new Counter("release_bad");
const http_500 = new Counter("http_500");

const hold_latency = new Trend("hold_latency", true);
const release_latency = new Trend("release_latency", true);

export const options = {
    vus: VUS,
    duration: DURATION,
    thresholds: {
        http_500: ["count==0"],
        hold_latency: ["p(95)<200"],
        release_latency: ["p(95)<200"],
    },
};

const holdParams = {
    headers: {
        "Content-Type": "application/json",
        "X-LOADTEST-BYPASS": "true",
    },
};
const releaseParams = {
    headers: {
        "Content-Type": "application/json",
    },
};

function userId() {
    return 100000 + __VU;
}

function seatNoRandom() {
    // iteration마다 다른 좌석을 고르도록 랜덤
    const n = Math.floor(Math.random() * SEATS_SAMPLE) + 1; // 1..SEATS_SAMPLE
    return `A-${n}`;
}

export default function () {
    const uid = userId();
    const seatNo = seatNoRandom();

    // 1) HOLD
    const holdBody = JSON.stringify({ scheduleId: SCHEDULE_ID, seatNo, userId: uid });
    const t0 = Date.now();
    const h = http.post(`${BASE_URL}/api/tickets/hold`, holdBody, holdParams);
    hold_latency.add(Date.now() - t0);

    const holdOkOr409 = check(h, { "hold is 2xx/409": (r) => (r.status >= 200 && r.status < 300) || r.status === 409 });
    if (h.status >= 500) http_500.add(1);

    if (!holdOkOr409) {
        hold_etc.add(1);
        // hold가 이상하면 다음으로 안 감
        sleep(0.01);
        return;
    }

    if (h.status === 409) {
        hold_409.add(1);
        sleep(0.01);
        return; // 내 좌석이 이미 잡혀있으면 release 하면 안 됨
    }

    hold_ok.add(1);

    // (선택) 결제 대기 흉내
    sleep(0.01);

    // 2) RELEASE (hold 성공한 것만)
    const releaseBody = JSON.stringify({ scheduleId: SCHEDULE_ID, seatNo, userId: uid });
    const t1 = Date.now();
    const r = http.post(`${BASE_URL}/api/tickets/release`, releaseBody, releaseParams);
    release_latency.add(Date.now() - t1);

    const releaseOk = check(r, { "release is 2xx": (resp) => resp.status >= 200 && resp.status < 300 });
    if (r.status >= 500) http_500.add(1);

    if (releaseOk) {
        release_ok.add(1);
    } else {
        release_bad.add(1);
        // 문제 재현용 덤프(너무 많이 찍히면 주석)
        console.log(`release bad status=${r.status} body=${r.body}`);
    }

    sleep(0.01);
}
