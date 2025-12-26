import http from "k6/http";
import { check, sleep } from "k6";
import exec from "k6/execution";
import { Counter, Rate, Trend } from "k6/metrics";

/**
 * ===== 환경변수 =====
 * BASE_URL     (default: http://localhost:8080)
 * SCHEDULE_ID  (default: 1)
 * MODE         (default: random)  // random | hot
 * HOT_SEAT     (default: A-1)     // MODE=hot 일 때만 사용
 * SEAT_PREFIX  (default: A-)
 * SEAT_COUNT   (default: 50)      // A-1 ~ A-50
 *
 * VUS          (default: 100)
 * DURATION     (default: 30s)
 *
 * ENTER_QUEUE  (default: 0)       // 1이면 /api/queue/enter 먼저 호출
 * RUN_PAYMENT  (default: 0)       // 1이면 hold 성공 시 일부 결제까지
 * PAY_RATIO    (default: 0.2)     // 결제 비율(0~1)
 * RELEASE_ON_SUCCESS (default: 1) // 결제 안 타면 release 할지
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || 1);

const MODE = (__ENV.MODE || "random").toLowerCase();
const HOT_SEAT = __ENV.HOT_SEAT || "A-1";

const SEAT_PREFIX = __ENV.SEAT_PREFIX || "A-";
const SEAT_COUNT = Number(__ENV.SEAT_COUNT || 50);

const ENTER_QUEUE = (__ENV.ENTER_QUEUE || "0") === "1";
const RUN_PAYMENT = (__ENV.RUN_PAYMENT || "0") === "1";
const PAY_RATIO = Number(__ENV.PAY_RATIO || 0.2);
const RELEASE_ON_SUCCESS = (__ENV.RELEASE_ON_SUCCESS || "1") === "1";

const VUS = Number(__ENV.VUS || 100);
const DURATION = __ENV.DURATION || "30s";

const HOLD_URL = `${BASE_URL}/api/tickets/hold`;
const RELEASE_URL = `${BASE_URL}/api/tickets/release`;
const QUEUE_ENTER_URL = `${BASE_URL}/api/queue/enter`;
const PAYMENT_READY_URL = `${BASE_URL}/api/payment/ready`;
const PAYMENT_SUCCESS_URL = `${BASE_URL}/api/payment/mock-success`;

const headers = { "Content-Type": "application/json" };

// ===== Metrics =====
const holdOk = new Counter("hold_ok");
const holdFail = new Counter("hold_fail");
const holdConflict = new Counter("hold_conflict"); // 409
const httpErr = new Counter("http_err");
const holdLatency = new Trend("hold_latency_ms");

const payOk = new Counter("pay_ok");
const payFail = new Counter("pay_fail");

const successRate = new Rate("success_rate");

export const options = {
    vus: VUS,
    duration: DURATION,
    thresholds: {
        success_rate: ["rate>0.90"], // 상황에 맞게 조절
        http_req_failed: ["rate<0.05"],
    },
};

function randInt(min, maxInclusive) {
    return Math.floor(Math.random() * (maxInclusive - min + 1)) + min;
}

function pickSeatNo() {
    if (MODE === "hot") return HOT_SEAT;
    return `${SEAT_PREFIX}${randInt(1, SEAT_COUNT)}`;
}

function makeUserId() {
    // VU/ITER 기반 유니크 userId
    return exec.vu.idInTest * 1000000 + exec.scenario.iterationInTest;
}

function safeJson(res) {
    try {
        return res.json();
    } catch (e) {
        return null;
    }
}

function enterQueue(scheduleId, userId) {
    const payload = JSON.stringify({ scheduleId, userId });
    const res = http.post(QUEUE_ENTER_URL, payload, { headers, tags: { name: "queue_enter" } });
    // queue는 실패해도 hold로 진행(서비스에서 auto-enter도 할 수 있게 해둔 전제)
    return res;
}

function holdSeat(scheduleId, seatNo, userId) {
    const payload = JSON.stringify({ scheduleId, seatNo, userId });
    const res = http.post(HOLD_URL, payload, { headers, tags: { name: "hold" } });
    holdLatency.add(res.timings.duration);

    if (res.status >= 500) httpErr.add(1);
    if (res.status === 409) holdConflict.add(1);

    return res;
}

function releaseSeat(scheduleId, seatNo, userId) {
    const payload = JSON.stringify({ scheduleId, seatNo, userId });
    return http.post(RELEASE_URL, payload, { headers, tags: { name: "release" } });
}

function paymentFlow(scheduleId, seatNo, userId) {
    const readyPayload = JSON.stringify({ userId, scheduleId, seatNo });
    const readyRes = http.post(PAYMENT_READY_URL, readyPayload, { headers, tags: { name: "payment_ready" } });

    if (readyRes.status !== 200) {
        payFail.add(1);
        return false;
    }

    const readyBody = safeJson(readyRes);
    const orderNo = readyBody && readyBody.orderNo;

    if (!orderNo) {
        payFail.add(1);
        return false;
    }

    const successPayload = JSON.stringify({ orderNo });
    const successRes = http.post(PAYMENT_SUCCESS_URL, successPayload, { headers, tags: { name: "payment_success" } });

    if (successRes.status !== 200) {
        payFail.add(1);
        return false;
    }

    const successBody = safeJson(successRes);
    const ok = successBody && successBody.success === true;
    if (ok) payOk.add(1);
    else payFail.add(1);

    return ok;
}

export default function () {
    const seatNo = pickSeatNo();
    const userId = makeUserId();

    if (ENTER_QUEUE) {
        enterQueue(SCHEDULE_ID, userId);
    }

    const res = holdSeat(SCHEDULE_ID, seatNo, userId);
    const body = safeJson(res);

    const ok =
        res.status === 200 &&
        body &&
        typeof body.success === "boolean" &&
        body.success === true;

    check(res, {
        "hold http ok(200/409/400)": (r) => [200, 400, 409].includes(r.status),
    });

    if (ok) {
        holdOk.add(1);
        successRate.add(true);

        // 결제 일부만 태우기
        if (RUN_PAYMENT && Math.random() < PAY_RATIO) {
            paymentFlow(SCHEDULE_ID, seatNo, userId);
            // 결제 성공이면 일반적으로 release 안 함(좌석 확정)
        } else {
            if (RELEASE_ON_SUCCESS) {
                releaseSeat(SCHEDULE_ID, seatNo, userId);
            }
        }
    } else {
        holdFail.add(1);
        successRate.add(false);
    }

    // 너무 쎄면 서버가 아예 죽으니 약간 숨 고르기(원하면 0으로)
    sleep(0.01);
}
