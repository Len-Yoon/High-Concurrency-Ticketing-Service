import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';

// 409(좌석 이미 락)도 expected로 처리해서 http_req_failed 개선
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

// =====================
// ENV
// =====================
const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || '1');

const VUS = Number(__ENV.VUS || '100');
const DURATION = __ENV.DURATION || '30s';

// constant-vus | arrival
const SCENARIO = String(__ENV.SCENARIO || 'constant-vus').toLowerCase();

// 사용자 풀
const USER_POOL = Number(__ENV.USER_POOL || String(Math.min(Math.max(VUS, 1), 100)));
const PRESEED_QUEUE = String(__ENV.PRESEED_QUEUE || '1') === '1';

// random | race | available
const MODE = String(__ENV.MODE || 'random').toLowerCase();

// 좌석 소스
const USE_SEAT_LIST = String(__ENV.USE_SEAT_LIST || '1') === '1';
const SEAT_LIST_PATH = __ENV.SEAT_LIST_PATH || `/api/schedules/${SCHEDULE_ID}/seats`;

// available 모드에서만 사용
const AVAILABLE_SEAT_PATH = __ENV.AVAILABLE_SEAT_PATH || `/api/seats/available?scheduleId=${SCHEDULE_ID}`;

// range fallback (seat list 없을 때만 의미 있음)
const SEAT_PREFIX = __ENV.SEAT_PREFIX || 'A';
const SEAT_SEP = (__ENV.SEAT_SEP !== undefined) ? String(__ENV.SEAT_SEP) : '';
const SEAT_START = Number(__ENV.SEAT_START || '1');
const SEAT_END = Number(__ENV.SEAT_END || '50');

// race 모드 타겟
const TARGET_SEAT = __ENV.TARGET_SEAT || `${SEAT_PREFIX}${SEAT_SEP}${SEAT_START}`;

// 엔드포인트
const HOLD_PATH = __ENV.HOLD_PATH || '/api/tickets/hold';
const RELEASE_PATH = __ENV.RELEASE_PATH || '/api/tickets/release';
const QUEUE_ENTER_PATH = __ENV.QUEUE_ENTER_PATH || '/api/queue/enter';

const HOLD_URL = `${BASE_URL}${HOLD_PATH}`;
const RELEASE_URL = `${BASE_URL}${RELEASE_PATH}`;
const QUEUE_ENTER_URL = `${BASE_URL}${QUEUE_ENTER_PATH}`;
const SEAT_LIST_URL = `${BASE_URL}${SEAT_LIST_PATH}`;
const AVAILABLE_SEAT_URL = `${BASE_URL}${AVAILABLE_SEAT_PATH}`;

const RELEASE_AFTER = String(__ENV.RELEASE_AFTER || '1') === '1';

// ✅ 핵심: 2xx인데 success=false인 케이스도 정리용 release 시도
const RELEASE_ON_BIZ_FAIL = String(__ENV.RELEASE_ON_BIZ_FAIL || '1') === '1';

// seat pick 전략(충돌 줄이기)
// - per-vu: 같은 userId는 같은 좌석부터 시도(성공이 한번이라도 나오게 만들기 좋음)
// - random: 완전 랜덤
const SEAT_PICK = String(__ENV.SEAT_PICK || 'per-vu').toLowerCase(); // per-vu | random

const THINK_TIME = Number(__ENV.SLEEP || '0.2');

// logs (초반만)
const LOG_409 = Number(__ENV.LOG_409 || '10');
const LOG_BIZFAIL = Number(__ENV.LOG_BIZFAIL || '10');
const LOG_NETERR = Number(__ENV.LOG_NETERR || '10');

// thresholds
const HOLD_P95_MS = Number(__ENV.HOLD_P95_MS || '800');
const OK_RATE_MIN = Number(__ENV.OK_RATE_MIN || '0.98');
const BAD_REQUEST_MAX = Number(__ENV.BAD_REQUEST_MAX || '0.01');

// 비즈니스 성공률 임계값은 기본 비활성
const BIZ_SUCCESS_MIN_RAW = __ENV.BIZ_SUCCESS_MIN;
const BIZ_SUCCESS_MIN =
    (BIZ_SUCCESS_MIN_RAW !== undefined && BIZ_SUCCESS_MIN_RAW !== null && String(BIZ_SUCCESS_MIN_RAW).trim() !== '')
        ? Number(BIZ_SUCCESS_MIN_RAW)
        : null;

// arrival scenario settings
const START_RATE = Number(__ENV.START_RATE || '0');
const PREALLOC_VUS = Number(__ENV.PREALLOC_VUS || String(Math.max(VUS, USER_POOL)));
const MAX_VUS = Number(__ENV.MAX_VUS || String(Math.max(PREALLOC_VUS * 5, PREALLOC_VUS)));
const STAGE1 = __ENV.STAGE1 || '5s';
const STAGE2 = __ENV.STAGE2 || '10s';
const STAGE3 = __ENV.STAGE3 || '5s';
const RATE1 = Number(__ENV.RATE1 || '300');
const RATE2 = Number(__ENV.RATE2 || '600');
const RATE3 = Number(__ENV.RATE3 || '0');

// =====================
// Metrics
// =====================
const holdDuration = new Trend('hold_duration');
const releaseDuration = new Trend('release_duration');
const seatFetchDuration = new Trend('seat_fetch_duration');
const queueEnterDuration = new Trend('queue_enter_duration');

const okRate = new Rate('ok_rate');                  // HTTP(2xx/409) OK
const bizSuccessRate = new Rate('biz_success_rate'); // 2xx + success=true
const serverErrorRate = new Rate('server_error_rate');
const badRequestRate = new Rate('bad_request_rate');
const networkErrorRate = new Rate('network_error_rate');

const holdSuccess = new Counter('hold_success');
const holdConflict = new Counter('hold_conflict');      // 409
const holdBizFail = new Counter('hold_biz_fail');        // 2xx + success=false
const holdBadRequest = new Counter('hold_bad_request');  // 4xx except 409
const holdNotFound = new Counter('hold_not_found');      // 404
const holdServerError = new Counter('hold_server_error');// 5xx
const holdOther = new Counter('hold_other');

const releaseAttempt = new Counter('release_attempt');
const releaseOk = new Counter('release_ok');
const releaseFail = new Counter('release_fail');

function isJson(res) {
    const ct = res.headers && (res.headers['Content-Type'] || res.headers['content-type']);
    return !!ct && String(ct).includes('application/json');
}

function safeJson(res) {
    if (!isJson(res)) return null;
    try { return JSON.parse(res.body); } catch (_) { return null; }
}

function getErrorCode(res) {
    const body = safeJson(res);
    return body && body.code ? String(body.code) : null;
}

// 2xx여도 {success:false}가 올 수 있음
function getHoldBizSuccess(res) {
    const body = safeJson(res);
    if (!body) return true; // json 아니면 일단 성공 취급
    if (typeof body.success === 'boolean') return body.success;
    return true;
}

function pickRandom(arr) {
    if (!arr || arr.length === 0) return null;
    return arr[Math.floor(Math.random() * arr.length)];
}

function randomSeatByRange() {
    const n = Math.floor(Math.random() * (SEAT_END - SEAT_START + 1)) + SEAT_START;
    return `${SEAT_PREFIX}${SEAT_SEP}${n}`;
}

function userIdForThisVU() {
    return ((exec.vu.idInTest - 1) % USER_POOL) + 1;
}

function vuIteration() {
    // k6 버전마다 없을 수 있어 fallback
    return (exec.vu && typeof exec.vu.iterationInScenario === 'number') ? exec.vu.iterationInScenario : 0;
}

function buildOptions() {
    const thresholds = {
        server_error_rate: ['rate==0'],
        bad_request_rate: [`rate<${BAD_REQUEST_MAX}`],
        ok_rate: [`rate>${OK_RATE_MIN}`],
        hold_duration: [`p(95)<${HOLD_P95_MS}`],
    };
    if (BIZ_SUCCESS_MIN !== null && !Number.isNaN(BIZ_SUCCESS_MIN)) {
        thresholds.biz_success_rate = [`rate>${BIZ_SUCCESS_MIN}`];
    }

    if (SCENARIO === 'arrival') {
        return {
            thresholds,
            scenarios: {
                traffic: {
                    executor: 'ramping-arrival-rate',
                    startRate: START_RATE,
                    timeUnit: '1s',
                    preAllocatedVUs: PREALLOC_VUS,
                    maxVUs: MAX_VUS,
                    stages: [
                        { target: RATE1, duration: STAGE1 },
                        { target: RATE2, duration: STAGE2 },
                        { target: RATE3, duration: STAGE3 },
                    ],
                },
            },
        };
    }

    return { thresholds, vus: VUS, duration: DURATION };
}

export const options = buildOptions();

// =====================
// setup()
// =====================
export function setup() {
    let seatNos = [];

    if (USE_SEAT_LIST) {
        const res = http.get(SEAT_LIST_URL, { tags: { endpoint: 'seat_list' } });
        const arr = safeJson(res);
        if (res.status === 200 && Array.isArray(arr)) {
            seatNos = arr
                .map((x) => (x && (x.seatNo || x.seat_no || x.seat)) ? String(x.seatNo || x.seat_no || x.seat) : null)
                .filter((x) => !!x);
        }
    }

    if (PRESEED_QUEUE) {
        for (let uid = 1; uid <= USER_POOL; uid++) {
            const payload = JSON.stringify({ scheduleId: SCHEDULE_ID, userId: uid });
            const res = http.post(QUEUE_ENTER_URL, payload, {
                headers: { 'Content-Type': 'application/json' },
                tags: { endpoint: 'queue_enter' },
            });
            queueEnterDuration.add(res.timings.duration);
        }
    }

    // seat list 크기 힌트 (SEAT_END는 seat list 쓰면 의미 거의 없음)
    if (exec.vu.idInTest === 1) {
        console.log(`setup: seatNos=${seatNos.length} (USE_SEAT_LIST=${USE_SEAT_LIST})`);
    }

    return { seatNos };
}

function doRelease(payload, userId, seatNo, reason) {
    releaseAttempt.add(1);
    const r2 = http.post(RELEASE_URL, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: 'release' },
    });
    releaseDuration.add(r2.timings.duration);

    const ok = (r2.status >= 200 && r2.status < 300);
    if (ok) releaseOk.add(1);
    else releaseFail.add(1);

    if (!ok && exec.scenario.iterationInTest < 20) {
        console.warn(`release fail(${reason}) user=${userId} seat=${seatNo} status=${r2.status} body=${r2.body}`);
    }
    return ok;
}

// =====================
// main
// =====================
export default function (data) {
    const userId = userIdForThisVU();

    // seat 결정
    let seatNo = null;

    if (MODE === 'race') {
        seatNo = TARGET_SEAT;
    } else if (MODE === 'available') {
        const t0 = Date.now();
        const res = http.get(AVAILABLE_SEAT_URL, { tags: { endpoint: 'seat_available' } });
        seatFetchDuration.add(Date.now() - t0);

        const arr = safeJson(res);
        if (res.status === 200 && Array.isArray(arr) && arr.length > 0) {
            const picked = pickRandom(arr);
            seatNo = picked && (picked.seatNo || picked.seat_no || picked.seat);
        }

        if (!seatNo) {
            sleep(THINK_TIME);
            return;
        }
    } else {
        // random
        const seats = (data && data.seatNos) ? data.seatNos : [];
        if (seats.length > 0) {
            if (SEAT_PICK === 'per-vu') {
                const idx = (userId - 1 + vuIteration()) % seats.length;
                seatNo = seats[idx];
            } else {
                seatNo = pickRandom(seats);
            }
        } else {
            seatNo = randomSeatByRange();
        }
    }

    const payload = JSON.stringify({ userId, scheduleId: SCHEDULE_ID, seatNo });
    const res = http.post(HOLD_URL, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: 'hold' },
    });

    // 네트워크 에러(서버 다운 등)
    if (res.status === 0) {
        networkErrorRate.add(true);
        okRate.add(false);
        bizSuccessRate.add(false);
        if (exec.scenario.iterationInTest < LOG_NETERR) {
            console.warn(`network error user=${userId} seat=${seatNo} err=${res.error || 'unknown'}`);
        }
        sleep(THINK_TIME);
        return;
    } else {
        networkErrorRate.add(false);
    }

    holdDuration.add(res.timings.duration);

    const is2xx = (res.status >= 200 && res.status < 300);
    const bizSuccess = is2xx && getHoldBizSuccess(res);
    const httpOk = is2xx || res.status === 409;

    okRate.add(httpOk);
    bizSuccessRate.add(bizSuccess);
    serverErrorRate.add(res.status >= 500);
    badRequestRate.add(res.status >= 400 && res.status < 500 && res.status !== 409);

    const code = getErrorCode(res) || 'NO_CODE';

    if (bizSuccess) {
        holdSuccess.add(1);

        if (RELEASE_AFTER) {
            doRelease(payload, userId, seatNo, 'after_success');
        }

    } else if (res.status === 409) {
        holdConflict.add(1, { code });

        if (exec.scenario.iterationInTest < LOG_409) {
            console.log(`409 user=${userId} seat=${seatNo} code=${code} body=${res.body}`);
        }

    } else if (is2xx && !bizSuccess) {
        holdBizFail.add(1, { code: 'HOLD_SUCCESS_FALSE' });

        if (exec.scenario.iterationInTest < LOG_BIZFAIL) {
            console.warn(`2xx success=false user=${userId} seat=${seatNo} body=${res.body}`);
        }

        // ✅ 핵심: 성공=false여도 혹시 "내 락"이 남아있을 수 있어서 정리 시도
        if (RELEASE_ON_BIZ_FAIL) {
            doRelease(payload, userId, seatNo, 'biz_fail_cleanup');
        }

    } else if (res.status === 404) {
        holdNotFound.add(1, { code });

    } else if (res.status >= 400 && res.status < 500) {
        holdBadRequest.add(1, { code });

    } else if (res.status >= 500) {
        holdServerError.add(1, { code });
        if (exec.scenario.iterationInTest < 20) {
            console.error(`5xx user=${userId} seat=${seatNo} status=${res.status} code=${code} body=${res.body}`);
        }

    } else {
        holdOther.add(1, { code });
    }

    check(res, {
        'hold: status is 2xx or 409': () => httpOk,
    });

    sleep(THINK_TIME);
}
