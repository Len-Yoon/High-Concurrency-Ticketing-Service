import http from "k6/http";
import { check, sleep } from "k6";

export const options = { vus: 1, iterations: 1 };

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

const QUEUE_ENTER_PATH = __ENV.QUEUE_ENTER_PATH || "/api/queue/enter";
const HOLD_PATH = __ENV.HOLD_PATH || "/api/reservations/hold";
const CONFIRM_PATH = __ENV.CONFIRM_PATH || "/api/payments/mock/success";

const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || 1);
const SEAT_NO = __ENV.SEAT_NO || "A1";   // 핵심: seatId 아님
const USER_ID = Number(__ENV.USER_ID || 101);
const PRICE = Number(__ENV.PRICE || 10000);

// true면 queue enter 없이 hold (컨트롤러의 X-LOADTEST-BYPASS 사용)
const BYPASS = String(__ENV.BYPASS || "false").toLowerCase() === "true";

function is2xx(r) { return r && r.status >= 200 && r.status < 300; }
function safeJson(r) { try { return r.json(); } catch (_) { return {}; } }
function cut(s, n = 300) { return String(s ?? "").slice(0, n); }

function pick(obj, paths) {
    for (const p of paths) {
        const v = p.split(".").reduce((a, k) => (a && a[k] !== undefined ? a[k] : undefined), obj);
        if (v !== undefined && v !== null && v !== "") return v;
    }
    return null;
}

function postJson(url, body, headers = {}) {
    return http.post(url, JSON.stringify(body), {
        headers: { "Content-Type": "application/json", ...headers },
        timeout: "30s",
    });
}

function queueEnter(scheduleId, userId) {
    // POST 우선
    const postRes = postJson(`${BASE_URL}${QUEUE_ENTER_PATH}`, { scheduleId, userId }, { "X-USER-ID": String(userId) });
    if (is2xx(postRes)) return postRes;

    // GET fallback
    return http.get(
        `${BASE_URL}${QUEUE_ENTER_PATH}?scheduleId=${encodeURIComponent(scheduleId)}&userId=${encodeURIComponent(userId)}`,
        { headers: { "X-USER-ID": String(userId) }, timeout: "30s" }
    );
}

export default function () {
    let queueToken = null;

    if (!BYPASS) {
        const enterRes = queueEnter(SCHEDULE_ID, USER_ID);
        check(enterRes, { "queue enter 2xx": (r) => is2xx(r) });

        if (!is2xx(enterRes)) {
            console.log(`[STOP] queue enter failed: ${enterRes.status} ${cut(enterRes.body)}`);
            return;
        }

        const ej = safeJson(enterRes);
        queueToken = pick(ej, ["token", "queueToken", "passToken", "data.token", "data.queueToken", "data.passToken"]);

        if (!queueToken) {
            console.log(`[STOP] queue token missing: ${cut(enterRes.body)}`);
            return;
        }
    }

    // hold: scheduleId + seatNo + userId
    const holdHeaders = { "X-USER-ID": String(USER_ID) };
    if (BYPASS) holdHeaders["X-LOADTEST-BYPASS"] = "true";
    if (queueToken) holdHeaders["X-QUEUE-TOKEN"] = String(queueToken);

    const holdPayload = {
        scheduleId: SCHEDULE_ID,
        seatNo: SEAT_NO,
        userId: USER_ID,
    };

    const holdRes = postJson(`${BASE_URL}${HOLD_PATH}`, holdPayload, holdHeaders);
    check(holdRes, { "hold 2xx": (r) => is2xx(r) });

    if (!is2xx(holdRes)) {
        console.log(`[STOP] hold failed: ${holdRes.status} body=${cut(holdRes.body)} payload=${JSON.stringify(holdPayload)}`);
        return;
    }

    const holdJson = safeJson(holdRes);

    // 프로젝트마다 reservationId 위치가 다름 + message 안 숫자 fallback
    let reservationId = pick(holdJson, [
        "reservationId",
        "id",
        "data.reservationId",
        "data.id",
    ]);

    if (!reservationId && holdJson && holdJson.message) {
        const m = String(holdJson.message).match(/\b\d+\b/);
        if (m) reservationId = Number(m[0]);
    }

    if (!reservationId) {
        console.log(`[HOLD-OK] hold 성공. 하지만 응답에 reservationId가 없음: ${cut(holdRes.body)}`);
        return; // confirm 계약 확인 후 연결
    }

    // confirm
    const confirmRes = postJson(`${BASE_URL}${CONFIRM_PATH}`, {
        reservationId,
        orderId: `ORD-${Date.now()}`,
        paymentKey: `PK-${Date.now()}`,
        amount: PRICE,
    });

    check(confirmRes, { "confirm 2xx": (r) => is2xx(r) });

    if (!is2xx(confirmRes)) {
        console.log(`[STOP] confirm failed: ${confirmRes.status} ${cut(confirmRes.body)}`);
        return;
    }

    console.log(`[DONE] hold=${holdRes.status}, confirm=${confirmRes.status}, reservationId=${reservationId}`);
    sleep(1);
}
