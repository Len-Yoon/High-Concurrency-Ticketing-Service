import http from "k6/http";
import { sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || "1");
const SEAT_NO = (__ENV.SEAT_NO || "A-1").trim().toUpperCase();

export const options = { vus: 5, duration: "3s" };

function jsonHeaders() {
    return { headers: { "Content-Type": "application/json" } };
}

function uid() {
    return 400000 + __VU;
}

function logIfBad(tag, res) {
    if (res.status >= 200 && res.status < 300) return;
    console.log(`${tag} status=${res.status} body=${String(res.body).slice(0, 500)}`);
}

export default function () {
    const body = JSON.stringify({ scheduleId: SCHEDULE_ID, seatNo: SEAT_NO, userId: uid() });

    const h = http.post(`${BASE_URL}/api/tickets/hold`, body, jsonHeaders());
    logIfBad("hold", h);

    const r1 = http.post(`${BASE_URL}/api/tickets/release`, body, jsonHeaders());
    logIfBad("release1", r1);

    const r2 = http.post(`${BASE_URL}/api/tickets/release`, body, jsonHeaders());
    logIfBad("release2", r2);

    sleep(0.05);
}
