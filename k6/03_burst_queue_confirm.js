import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

export const options = {
    scenarios: {
        burst: {
            executor: "ramping-arrival-rate",
            startRate: 20,
            timeUnit: "1s",
            preAllocatedVUs: 100,
            maxVUs: 300,
            stages: [
                { target: 50, duration: "30s" },
                { target: 100, duration: "30s" },
                { target: 0, duration: "20s" },
            ],
        },
    },
    thresholds: {
        http_req_failed: ["rate<0.05"],
        http_req_duration: ["p(95)<1000"],
    },
};

const BASE = __ENV.BASE_URL || "http://127.0.0.1:8080";
const SEAT_PREFIX = __ENV.SEAT_PREFIX || "A";   // 기본 A좌석
const SEAT_COUNT = Number(__ENV.SEAT_COUNT || 100);

const tokenMissing = new Counter("token_missing");
const holdOk = new Counter("hold_200");
const hold409 = new Counter("hold_409");
const hold404 = new Counter("hold_404");
const holdOther = new Counter("hold_other");
const confirmFail = new Counter("confirm_fail");

export default function () {
    const scheduleId = 1;
    const userId = __VU * 100000 + __ITER + 1;
    const seatNo = `${SEAT_PREFIX}${(userId % SEAT_COUNT) + 1}`; // A1~A100

    // 1) queue enter
    const enterRes = http.post(
        `${BASE}/api/queue/enter`,
        JSON.stringify({ scheduleId, userId }),
        { headers: { "Content-Type": "application/json" } }
    );
    check(enterRes, { "enter 200": (r) => r.status === 200 });

    const token = enterRes.json("token");
    if (!token) {
        tokenMissing.add(1);
        sleep(0.05);
        return;
    }

    // 2) hold
    const holdRes = http.post(
        `${BASE}/api/tickets/hold`,
        JSON.stringify({ scheduleId, seatNo, userId }),
        {
            headers: {
                "Content-Type": "application/json",
                "X-QUEUE-TOKEN": token,
            },
        }
    );

    if (holdRes.status === 200) {
        holdOk.add(1);
    } else if (holdRes.status === 409) {
        hold409.add(1);
    } else if (holdRes.status === 404) {
        hold404.add(1);
    } else {
        holdOther.add(1);
        console.log(`hold unexpected status=${holdRes.status} body=${holdRes.body}`);
    }

    check(holdRes, { "hold 200": (r) => r.status === 200 });

    // hold 실패 시 confirm 스킵
    if (holdRes.status !== 200) return;

    // 3) confirm (outbox enqueue)
    const confirmRes = http.post(
        `${BASE}/api/tickets/confirm`,
        JSON.stringify({ scheduleId, seatNo, userId }),
        { headers: { "Content-Type": "application/json" } }
    );

    const confirmOk = check(confirmRes, { "confirm 200": (r) => r.status === 200 });
    if (!confirmOk) {
        confirmFail.add(1);
        console.log(`confirm fail status=${confirmRes.status} body=${confirmRes.body}`);
    }
}
