import http from "k6/http";
import { sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEAT_NO = (__ENV.SEAT_NO || "A-1").trim().toUpperCase();

export const options = { vus: 5, iterations: 5 };

export default function () {
    const body = JSON.stringify({
        scheduleId: SCHEDULE_ID,
        seatNo: SEAT_NO,
        userId: 100000 + __VU,
    });

    const res = http.post(`${BASE_URL}/api/tickets/hold`, body, {
        headers: { "Content-Type": "application/json" },
    });

    // 응답 코드/바디(앞 300자) 출력
    console.log(
        `VU=${__VU} status=${res.status} body=${String(res.body).slice(0, 300)}`
    );

    sleep(0.1);
}
