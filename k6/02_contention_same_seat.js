import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

export const lockConflict = new Counter("seat_lock_conflict");

export const options = {
    vus: 80,
    duration: "30s",
    thresholds: {
        http_req_duration: ["p(95)<800"],
    },
};

const BASE = __ENV.BASE_URL || "http://127.0.0.1:8080";

export default function () {
    const scheduleId = 1;
    const seatNo = "A1";
    const userId = __VU * 100000 + __ITER + 1;

    // 경합만 보려면 queue bypass
    const res = http.post(
        `${BASE}/api/tickets/hold`,
        JSON.stringify({ scheduleId, seatNo, userId }),
        {
            headers: {
                "Content-Type": "application/json",
                "X-LOADTEST-BYPASS": "true",
            },
        }
    );

    const ok = res.status === 200;
    const conflict = res.status === 409;
    check(res, { "hold is 200 or 409": () => ok || conflict });

    if (conflict) lockConflict.add(1);
}
