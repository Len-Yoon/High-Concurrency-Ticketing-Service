import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEAT_NO = (__ENV.SEAT_NO || "A-1").trim().toUpperCase();
const VUS = Number(__ENV.VUS || "50");
const DURATION = __ENV.DURATION || "20s";

const hold_ok = new Counter("hold_ok");
const hold_409 = new Counter("hold_409");
const release_ok = new Counter("release_ok");
const release_bad = new Counter("release_bad");
const http_500 = new Counter("http_500");

const hold_latency = new Trend("hold_latency", true);
const release_latency = new Trend("release_latency", true);
const bad_rate = new Rate("bad_rate");

export const options = {
    vus: VUS,
    duration: DURATION,
    thresholds: {
        http_req_failed: ["rate<0.01"],
        bad_rate: ["rate<0.02"],
        "http_500": ["count==0"],                 // 여기서도 500은 0
        hold_latency: ["p(95)<300"],
        release_latency: ["p(95)<300"],
    },
};

function jsonHeaders() {
    return { headers: { "Content-Type": "application/json" } };
}

function userId() {
    return 200000 + __VU; // VU별 고정
}

function post(path, payload) {
    return http.post(`${BASE_URL}${path}`, JSON.stringify(payload), jsonHeaders());
}

export default function () {
    const uid = userId();
    const payload = { scheduleId: SCHEDULE_ID, seatNo: SEAT_NO, userId: uid };

    // 1) hold 시도
    let t0 = Date.now();
    const h = post("/api/tickets/hold", payload);
    hold_latency.add(Date.now() - t0);

    // 2) 바로 release 2번(멱등 확인: 두 번째는 이미 없을 수도 있음)
    t0 = Date.now();
    const r1 = post("/api/tickets/release", payload);
    release_latency.add(Date.now() - t0);

    t0 = Date.now();
    const r2 = post("/api/tickets/release", payload);
    release_latency.add(Date.now() - t0);

    // 기대:
    // - hold: 2xx 또는 409 정상
    // - release: 2xx가 정상 (이미 없어도 200 처리하도록 설계했지?)
    //   만약 네 API가 “없으면 404” 정책이면, 아래 체크에 404도 추가해줘.
    const ok = check(h, {
        "hold is 2xx or 409": (res) =>
            (res.status >= 200 && res.status < 300) || res.status === 409,
    }) && check(r1, {
        "release1 is 2xx": (res) => res.status >= 200 && res.status < 300,
    }) && check(r2, {
        "release2 is 2xx": (res) => res.status >= 200 && res.status < 300,
    });

    if (!ok) bad_rate.add(1);

    for (const res of [h, r1, r2]) {
        if (res.status >= 500) http_500.add(1);
    }

    if (h.status >= 200 && h.status < 300) hold_ok.add(1);
    else if (h.status === 409) hold_409.add(1);

    if (r1.status >= 200 && r1.status < 300) release_ok.add(1);
    else release_bad.add(1);

    if (r2.status >= 200 && r2.status < 300) release_ok.add(1);
    else release_bad.add(1);

    sleep(0.05);
}
