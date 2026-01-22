import http from "k6/http";
import { sleep } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEAT_NO = (__ENV.SEAT_NO || "A-1").trim().toUpperCase();

export const options = { vus: 100, duration: "60s" };

const dumped = new Counter("dumped_500");

function headers() {
    return { headers: { "Content-Type": "application/json" } };
}
function uid() {
    return 800000 + __VU;
}

function dump(tag, res, body) {
    // 500 바디는 길 수 있으니 앞 800자만
    console.log(`${tag} status=${res.status} body=${String(res.body).slice(0, 800)} req=${body}`);
    dumped.add(1);
}

export default function () {
    const body = JSON.stringify({ scheduleId: SCHEDULE_ID, seatNo: SEAT_NO, userId: uid() });

    const h = http.post(`${BASE_URL}/api/tickets/hold`, body, headers());
    if (h.status >= 200 && h.status < 300) {
        const r1 = http.post(`${BASE_URL}/api/tickets/release`, body, headers());
        if (r1.status >= 500 && dumped.value < 20) dump("release1", r1, body);

        const r2 = http.post(`${BASE_URL}/api/tickets/release`, body, headers());
        if (r2.status >= 500 && dumped.value < 20) dump("release2", r2, body);
    } else if (h.status >= 500 && dumped.value < 20) {
        dump("hold", h, body);
    }

    sleep(0.01);
}
