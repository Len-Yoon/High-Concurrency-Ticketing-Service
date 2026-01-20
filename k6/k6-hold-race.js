import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEAT_NO = (__ENV.SEAT_NO || "A-1").trim().toUpperCase();
const VUS = Number(__ENV.VUS || "100");
const ITERS = Number(__ENV.ITERS || "1");

const hold_ok = new Counter("hold_ok");
const hold_conflict = new Counter("hold_conflict");
const hold_bad = new Counter("hold_bad");
const http_500 = new Counter("http_500");

const hold_latency = new Trend("hold_latency", true);
const bad_rate = new Rate("bad_rate");

export const options = {
    vus: VUS,
    iterations: VUS * ITERS, // 각 VU가 1회씩만 때리게
    thresholds: {
        bad_rate: ["rate<0.02"],           // 이상 응답(예: 500, 400 등) 거의 없어야 함
        hold_latency: ["p(95)<300"],       // 로컬 기준 대충(원하면 조정)
        "http_500": ["count==0"],          // 500은 0이어야 함 (핵심)
    },
};

function jsonHeaders() {
    return { headers: { "Content-Type": "application/json" } };
}

function randUserId() {
    // VU별 고유 userId (충돌/멱등 테스트용)
    return 100000 + __VU;
}

export default function () {
    const body = JSON.stringify({
        scheduleId: SCHEDULE_ID,
        seatNo: SEAT_NO,
        userId: randUserId(),
    });

    const t0 = Date.now();
    const res = http.post(`${BASE_URL}/api/tickets/hold`, body, jsonHeaders());
    hold_latency.add(Date.now() - t0);

    // 기대:
    // - 200(또는 201) 1명 정도
    // - 나머지는 409(ALREADY_HELD/ALREADY_RESERVED 등) 가 정상
    // - 500은 절대 안 됨
    const ok = check(res, {
        "hold status is 2xx or 409": (r) =>
            (r.status >= 200 && r.status < 300) || r.status === 409,
    });

    if (!ok) bad_rate.add(1);

    if (res.status >= 500) http_500.add(1);

    if (res.status >= 200 && res.status < 300) hold_ok.add(1);
    else if (res.status === 409) hold_conflict.add(1);
    else hold_bad.add(1);

    // 살짝 랜덤 지연(완전 동시성 유지하고 싶으면 제거)
    sleep(0.01);
}
