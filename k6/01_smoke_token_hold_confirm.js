import http from "k6/http";
import { check } from "k6";

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        http_req_failed: ["rate<0.01"],
        http_req_duration: ["p(95)<500"],
    },
};

const BASE = __ENV.BASE_URL || "http://127.0.0.1:8080";

export default function () {
    const scheduleId = 1;
    const userId = 1001;
    const seatNo = "A1";

    const enterRes = http.post(
        `${BASE}/api/queue/enter`,
        JSON.stringify({ scheduleId, userId }),
        { headers: { "Content-Type": "application/json" } }
    );
    check(enterRes, { "enter 200": (r) => r.status === 200 });

    const token = enterRes.json("token");
    check({ token }, { "token exists": (v) => !!v.token });

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
