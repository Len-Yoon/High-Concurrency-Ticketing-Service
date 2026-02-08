import http from "k6/http";
import { check, sleep } from "k6";

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

export default function () {
    const scheduleId = 1;
    const userId = __VU * 100000 + __ITER + 1;
    const seatNo = `C${(userId % 100) + 1}`;

    const enterRes = http.post(
        `${BASE}/api/queue/enter`,
        JSON.stringify({ scheduleId, userId }),
        { headers: { "Content-Type": "application/json" } }
    );
    check(enterRes, { "enter 200": (r) => r.status === 200 });

    const token = enterRes.json("token");
    if (!token) {
        sleep(0.05);
        return;
    }

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
    check(holdRes, { "hold 200": (r) => r.status === 200 });

    const confirmRes = http.post(
        `${BASE}/api/tickets/confirm`,
        JSON.stringify({ scheduleId, seatNo, userId }),
        { headers: { "Content-Type": "application/json" } }
    );
    check(confirmRes, { "confirm 200": (r) => r.status === 200 });
}
