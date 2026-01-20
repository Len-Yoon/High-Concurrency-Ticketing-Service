import http from "k6/http";
import { sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEAT_NO = (__ENV.SEAT_NO || "A-1").trim().toUpperCase();

export const options = { vus: 10, duration: "5s" }; // 짧게

function jsonHeaders() {
    return { headers: { "Content-Type": "application/json" } };
}

function uid() {
    return 300000 + __VU;
}

export default function () {
    const body = JSON.stringify({ scheduleId: SCHEDULE_ID, seatNo: SEAT_NO, userId: uid() });

    // hold 한번
    http.post(`${BASE_URL}/api/tickets/hold`, body, jsonHeaders());

    // release 두번
    const r1 = http.post(`${BASE_URL}/api/tickets/release`, body, jsonHeaders());
    const r2 = http.post(`${BASE_URL}/api/tickets/release`, body, jsonHeaders());

    // 500이면 바디 찍기(앞 400자)
    if (r1.status >= 500) console.log(`release1 status=${r1.status} body=${String(r1.body).slice(0, 400)}`);
    if (r2.status >= 500) console.log(`release2 status=${r2.status} body=${String(r2.body).slice(0, 400)}`);

    sleep(0.05);
}
