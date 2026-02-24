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
// canEnter=false면 token=null이므로, 충분히 기다리게 기본 120s
const MAX_WAIT_MS = __ENV.MAX_WAIT_MS ? Number(__ENV.MAX_WAIT_MS) : 120000;

const tPassWait = new Trend("queue_pass_wait_ms");
const cHold200 = new Counter("hold_200");
const cHold400 = new Counter("hold_400");
const cHold409 = new Counter("hold_409");
const cHoldEtc = new Counter("hold_other");

const cStatusNon200 = new Counter("queue_status_non200");
const cPassTimeout = new Counter("queue_pass_timeout");
const cTokenNullWhenCanEnter = new Counter("queue_canenter_token_null");

const JSON_HEADERS = { "Content-Type": "application/json" };

function queueEnter(sid, uid) {
    return http.post(
        `${BASE}/api/queue/enter`,
        JSON.stringify({ scheduleId: sid, userId: uid }),
        { headers: JSON_HEADERS }
    );
}

// ✅ GET 고정 (네 서버 스펙 확정)
function queueStatus(sid, uid) {
    return http.get(
        `${BASE}/api/queue/status?scheduleId=${sid}&userId=${uid}`,
        { headers: JSON_HEADERS }
    );
}

function extractToken(obj) {
    if (!obj) return null;

    // 네 응답에 token 키가 존재 (token=null/값)
    const direct = obj.token || obj.queueToken || obj.passToken;
    if (direct && typeof direct === "string" && direct.length > 0) return direct;

    if (obj.data) {
        const nested = obj.data.token || obj.data.queueToken || obj.data.passToken;
        if (nested && typeof nested === "string" && nested.length > 0) return nested;
    }
    return null;
}

function waitForPassToken(sid, uid) {
    const start = Date.now();
    queueEnter(sid, uid);

    let token = null;

    while (Date.now() - start < MAX_WAIT_MS) {
        const st = queueStatus(sid, uid);

        if (!st || st.status !== 200) {
            cStatusNon200.add(1);
            sleep(POLL_MS / 1000);
            continue;
        }

        const obj = st.json();
        const canEnter = obj && obj.canEnter === true;
        token = extractToken(obj);

        if (canEnter && token) {
            tPassWait.add(Date.now() - start);
            return token;
        }

        // canEnter=true인데 token이 아직 null일 수 있어서 기록해두고 계속 폴링
        if (canEnter && !token) cTokenNullWhenCanEnter.add(1);

        sleep(POLL_MS / 1000);
    }

    tPassWait.add(Date.now() - start);
    cPassTimeout.add(1);
    return null;
}

function seatIdForVU() {
    const range = Math.max(1, SEAT_TO - SEAT_FROM + 1);
    const idx = (__VU - 1) % range;
    return SEAT_FROM + idx;
}

function holdSeat(sid, seatId, uid, token) {
    const payload = {
        scheduleId: sid,
        seatId,
        userId: uid,
        bypassQueue: false,
        queueToken: token,
    };
    return http.post(`${BASE}/api/reservations/hold`, JSON.stringify(payload), {
        headers: JSON_HEADERS,
    });
}

export default function () {
    const uidBase = __ENV.UID_BASE ? Number(__ENV.UID_BASE) : 1000;
    const uid = uidBase + __VU;
    const seatId = seatIdForVU();

    const token = waitForPassToken(SID, uid);

    // token 못 받으면 hold는 400이 정상(의미 있는 관찰 대상)
    const res = holdSeat(SID, seatId, uid, token || "");

    check(res, { "hold got response": (r) => !!r });

    if (res.status === 200) cHold200.add(1);
    else if (res.status === 400) cHold400.add(1);
    else if (res.status === 409) cHold409.add(1);
    else cHoldEtc.add(1);

    sleep(0.1);
}