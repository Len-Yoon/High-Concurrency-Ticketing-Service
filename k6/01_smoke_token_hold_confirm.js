import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
    vus: 5,
    duration: "20s",
    thresholds: {
        http_req_failed: ["rate<0.01"],
        http_req_duration: ["p(95)<500"],
    },
};

const BASE = __ENV.BASE_URL || "http://127.0.0.1:8080";

export default function () {
    const userId = __VU * 100000 + __ITER + 1;
    const scheduleId = 1;
    const seatNo = `B${(userId % 50) + 1}`;

    // 1) queue enter
    const enterRes = http.post(
        `${BASE}/api/queue/enter`,
        JSON.stringify({ scheduleId, userId }),
        { headers: { "Content-Type": "application/json" } }
    );
    check(enterRes, { "enter 200": (r) => r.status === 200 });

    const enterBody = enterRes.json();
    const token = enterBody && enterBody.token;

    // token 없으면 이번 iteration skip
    if (!token) {
        sleep(0.2);
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
    check(holdRes, { "hold 200": (r) => r.status === 200 });

    // 3) confirm (outbox enqueue)
    const confirmRes = http.post(
        `${BASE}/api/tickets/confirm`,
        JSON.stringify({ scheduleId, seatNo, userId }),
        { headers: { "Content-Type": "application/json" } }
    );
    check(confirmRes, { "confirm 200": (r) => r.status === 200 });

    sleep(0.1);
}
