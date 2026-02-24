import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

export const options = {
    scenarios: {
        burst_queue_hold: {
            executor: "constant-vus",
            vus: __ENV.VUS ? Number(__ENV.VUS) : 100,
            duration: __ENV.DURATION || "30s",
        },
    },
    thresholds: {
        http_req_failed: ["rate<0.05"],
    },
};

const BASE = __ENV.BASE || "http://localhost:8080";
const SID = __ENV.SID ? Number(__ENV.SID) : 3;
const SEAT_FROM = __ENV.SEAT_FROM ? Number(__ENV.SEAT_FROM) : 1;
const SEAT_TO = __ENV.SEAT_TO ? Number(__ENV.SEAT_TO) : 100;

const POLL_MS = __ENV.POLL_MS ? Number(__ENV.POLL_MS) : 200;
const MAX_WAIT_MS = __ENV.MAX_WAIT_MS ? Number(__ENV.MAX_WAIT_MS) : 120000;

const tPassWait = new Trend("queue_pass_wait_ms");
const cHold200 = new Counter("hold_200");
const cHold400 = new Counter("hold_400");
const cHold409 = new Counter("hold_409");
const cHoldEtc = new Counter("hold_other");

const JSON_HEADERS = { "Content-Type": "application/json" };

// ---- queue API auto-detect ----
let detectedStatusMode = null;
/*
  status mode:
    - "GET_QS": GET /api/queue/status?scheduleId=..&userId=..
    - "POST_JSON": POST /api/queue/status {scheduleId,userId}
    - "GET_ALT": GET /api/queue/position?scheduleId=..&userId=..   (fallback)
    - "POST_ALT": POST /api/queue/check {scheduleId,userId}        (fallback)
*/
function detectQueueStatusMode() {
    if (detectedStatusMode) return detectedStatusMode;

    const uid = 999999; // dummy
    const candidates = [
        { mode: "GET_QS", method: "GET", url: `${BASE}/api/queue/status?scheduleId=${SID}&userId=${uid}` },
        { mode: "POST_JSON", method: "POST", url: `${BASE}/api/queue/status` },
        { mode: "GET_ALT", method: "GET", url: `${BASE}/api/queue/position?scheduleId=${SID}&userId=${uid}` },
        { mode: "POST_ALT", method: "POST", url: `${BASE}/api/queue/check` },
    ];

    for (const c of candidates) {
        let res;
        if (c.method === "GET") {
            res = http.get(c.url, { headers: JSON_HEADERS, timeout: "3s" });
        } else {
            res = http.post(c.url, JSON.stringify({ scheduleId: SID, userId: uid }), { headers: JSON_HEADERS, timeout: "3s" });
        }
        if (res && (res.status === 200 || res.status === 400)) {
            // 400이어도 "엔드포인트 존재"는 하는 케이스가 있어 mode로 채택
            detectedStatusMode = c.mode;
            return detectedStatusMode;
        }
    }
    detectedStatusMode = "GET_QS"; // default guess
    return detectedStatusMode;
}

// ---- enter ----
function queueEnter(sid, uid) {
    const url = `${BASE}/api/queue/enter`;
    const res = http.post(url, JSON.stringify({ scheduleId: sid, userId: uid }), { headers: JSON_HEADERS });
    return res;
}

// ---- status ----
function queueStatus(sid, uid) {
    return http.get(`${BASE}/api/queue/status?scheduleId=${sid}&userId=${uid}`, { headers: JSON_HEADERS });
}

// ---- parse token from responses ----
function extractToken(jsonObj) {
    if (!jsonObj) return null;

    // common fields
    const direct = jsonObj.token || jsonObj.queueToken || jsonObj.passToken;
    if (direct && typeof direct === "string" && direct.length > 0) return direct;

    // sometimes nested
    if (jsonObj.data && (jsonObj.data.token || jsonObj.data.queueToken)) {
        const t = jsonObj.data.token || jsonObj.data.queueToken;
        if (t && typeof t === "string" && t.length > 0) return t;
    }

    return null;
}

function isCanEnter(jsonObj) {
    if (!jsonObj) return false;
    if (typeof jsonObj.canEnter === "boolean") return jsonObj.canEnter;
    if (typeof jsonObj.allowed === "boolean") return jsonObj.allowed;
    if (typeof jsonObj.pass === "boolean") return jsonObj.pass;
    return false;
}

function waitForPassToken(sid, uid) {
    const start = Date.now();
    queueEnter(sid, uid);

    let token = null;
    let canEnter = false;

    while (Date.now() - start < MAX_WAIT_MS) {
        const st = queueStatus(sid, uid);

        if (st && st.status === 200) {
            const obj = st.json();

            canEnter = (obj && obj.canEnter === true);
            token = extractToken(obj);

            // canEnter가 true인데 token이 아직 null일 수 있어서,
            // true면 짧게 1~2번 더 폴링할 여지를 둠.
            if (canEnter && token) break;
        }

        sleep(POLL_MS / 1000);
    }

    tPassWait.add(Date.now() - start);
    return token; // token 없으면 null
}

function seatIdForVU() {
    const range = Math.max(1, SEAT_TO - SEAT_FROM + 1);
    const idx = (__VU - 1) % range;
    return SEAT_FROM + idx;
}

// ---- hold ----
function holdSeat(sid, seatId, uid, token) {
    const url = `${BASE}/api/reservations/hold`;
    const payload = {
        scheduleId: sid,
        seatId: seatId,
        userId: uid,
        bypassQueue: false,
        queueToken: token,
    };
    return http.post(url, JSON.stringify(payload), { headers: JSON_HEADERS });
}

export default function () {
    const uid = (__ENV.UID_BASE ? Number(__ENV.UID_BASE) : 1000) + __VU; // stable per VU
    const seatId = seatIdForVU();

    const token = waitForPassToken(SID, uid);

    // token 못 받으면 hold는 일부러 실패할 확률이 높음. 지표로 남기되, 테스트는 진행.
    const res = holdSeat(SID, seatId, uid, token || ""); // empty token => 400 expected

    check(res, {
        "hold response returned": (r) => !!r,
    });

    if (res.status === 200) cHold200.add(1);
    else if (res.status === 400) cHold400.add(1);
    else if (res.status === 409) cHold409.add(1);
    else cHoldEtc.add(1);

    // 짧게 숨 고르기
    sleep(0.1);
}