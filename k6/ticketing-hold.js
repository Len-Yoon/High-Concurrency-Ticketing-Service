import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';

// =====================
// ENV (k6: -e XXX=... / PowerShell: $env:XXX="...")
// =====================
const BASE_URL = ( __ENV.BASE_URL || 'http://localhost:8080' ).replace(/\/$/, '');
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || '1');

const VUS = Number(__ENV.VUS || '100');
const DURATION = __ENV.DURATION || '30s';

// constant-vus | arrival
const SCENARIO = String(__ENV.SCENARIO || 'constant-vus').toLowerCase();

// 사용자 풀(대기열 100 제한 때문에 기본 100)
const USER_POOL = Number(__ENV.USER_POOL || String(Math.min(Math.max(VUS, 1), 100)));
const PRESEED_QUEUE = String(__ENV.PRESEED_QUEUE || '1') === '1';

// random | race | available
const MODE = String(__ENV.MODE || 'random').toLowerCase();

// 좌석 소스
//  - USE_SEAT_LIST=1: /api/schedules/{id}/seats에서 seatNo 자동 수집(추천)
//  - 실패 시 range 기반 fallback
const USE_SEAT_LIST = String(__ENV.USE_SEAT_LIST || '1') === '1';
const SEAT_LIST_PATH = __ENV.SEAT_LIST_PATH || `/api/schedules/${SCHEDULE_ID}/seats`;

// available 모드에서만 사용(매 iter 잔여좌석 조회)
const AVAILABLE_SEAT_PATH = __ENV.AVAILABLE_SEAT_PATH || `/api/seats/available?scheduleId=${SCHEDULE_ID}`;

// range fallback
const SEAT_PREFIX = __ENV.SEAT_PREFIX || 'A';
const SEAT_SEP = (__ENV.SEAT_SEP !== undefined) ? String(__ENV.SEAT_SEP) : '';
const SEAT_START = Number(__ENV.SEAT_START || '1');
const SEAT_END = Number(__ENV.SEAT_END || '50');

// race 모드 타겟
const TARGET_SEAT = __ENV.TARGET_SEAT || `${SEAT_PREFIX}${SEAT_SEP}${SEAT_START}`;

// 엔드포인트
const HOLD_PATH = __ENV.HOLD_PATH || '/api/tickets/hold'; // 또는 /api/reservations/hold
const RELEASE_PATH = __ENV.RELEASE_PATH || '/api/tickets/release';
const QUEUE_ENTER_PATH = __ENV.QUEUE_ENTER_PATH || '/api/queue/enter';

const HOLD_URL = `${BASE_URL}${HOLD_PATH}`;
const RELEASE_URL = `${BASE_URL}${RELEASE_PATH}`;
const QUEUE_ENTER_URL = `${BASE_URL}${QUEUE_ENTER_PATH}`;
const SEAT_LIST_URL = `${BASE_URL}${SEAT_LIST_PATH}`;
const AVAILABLE_SEAT_URL = `${BASE_URL}${AVAILABLE_SEAT_PATH}`;

const RELEASE_AFTER = String(__ENV.RELEASE_AFTER || '1') === '1';
const THINK_TIME = Number(__ENV.SLEEP || '0.2');

// thresholds 튜닝
const HOLD_P95_MS = Number(__ENV.HOLD_P95_MS || '800');
const OK_RATE_MIN = Number(__ENV.OK_RATE_MIN || '0.98');
const BAD_REQUEST_MAX = Number(__ENV.BAD_REQUEST_MAX || '0.01');

// arrival scenario settings
const START_RATE = Number(__ENV.START_RATE || '0'); // req/s
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

const okRate = new Rate('ok_rate');
const serverErrorRate = new Rate('server_error_rate');
const badRequestRate = new Rate('bad_request_rate');

const holdSuccess = new Counter('hold_success');
const holdConflict = new Counter('hold_conflict');
const holdBadRequest = new Counter('hold_bad_request');
const holdNotFound = new Counter('hold_not_found');
const holdServerError = new Counter('hold_server_error');
const holdOther = new Counter('hold_other');

function isJson(res) {
  const ct = res.headers && (res.headers['Content-Type'] || res.headers['content-type']);
  return !!ct && String(ct).includes('application/json');
}

function getErrorCode(res) {
  if (!isJson(res)) return null;
  try {
    const body = JSON.parse(res.body);
    return body && body.code ? String(body.code) : null;
  } catch (_) {
    return null;
  }
}

function pickRandom(arr) {
  if (!arr || arr.length === 0) return null;
  return arr[Math.floor(Math.random() * arr.length)];
}

function randomSeatByRange() {
  const n = Math.floor(Math.random() * (SEAT_END - SEAT_START + 1)) + SEAT_START;
  return `${SEAT_PREFIX}${SEAT_SEP}${n}`; // 예: A1, A-1 등
}

function userIdForThisVU() {
  // VUS가 USER_POOL보다 커도, userId는 1..USER_POOL로 순환
  return ((exec.vu.idInTest - 1) % USER_POOL) + 1;
}

function buildOptions() {
  const thresholds = {
    server_error_rate: ['rate==0'],
    bad_request_rate: [`rate<${BAD_REQUEST_MAX}`],
    ok_rate: [`rate>${OK_RATE_MIN}`],
    hold_duration: [`p(95)<${HOLD_P95_MS}`],
  };

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

  return {
    thresholds,
    vus: VUS,
    duration: DURATION,
  };
}

export const options = buildOptions();

// =====================
// setup():
// 1) 좌석번호 자동 수집(가능하면)
// 2) 대기열 사전 진입(권장)
// =====================
export function setup() {
  let seatNos = [];

  if (USE_SEAT_LIST) {
    const res = http.get(SEAT_LIST_URL, { tags: { endpoint: 'seat_list' } });
    if (res.status === 200 && isJson(res)) {
      try {
        const arr = JSON.parse(res.body);
        if (Array.isArray(arr)) {
          seatNos = arr
            .map((x) => (x && (x.seatNo || x.seat_no || x.seat)) ? String(x.seatNo || x.seat_no || x.seat) : null)
            .filter((x) => !!x);
        }
      } catch (_) {
        // ignore
      }
    }
  }

  // 대기열은 Redis ZSET이라 테스트마다 누적됨.
  // 기존 queue:{scheduleId}가 남아있으면 rank>100이 되어 QUEUE_NOT_ALLOWED(400) 튀기 쉬움.
  // -> 가능하면 테스트 전에 Redis queue 키를 비우고(prefer), 여기서 1..USER_POOL만 미리 진입.
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

  return { seatNos };
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

    if (res.status === 200 && isJson(res)) {
      try {
        const arr = JSON.parse(res.body);
        if (Array.isArray(arr) && arr.length > 0) {
          const picked = pickRandom(arr);
          seatNo = picked.seatNo || picked.seat_no || picked.seat;
        }
      } catch (_) {
        // ignore
      }
    }

    if (!seatNo) {
      // 잔여좌석이 없거나(혹은 조회 실패), 그냥 쉬고 다음 iter
      sleep(THINK_TIME);
      return;
    }
  } else {
    // random
    seatNo = pickRandom(data && data.seatNos) || randomSeatByRange();
  }

  const payload = JSON.stringify({ userId, scheduleId: SCHEDULE_ID, seatNo });
  const res = http.post(HOLD_URL, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'hold' },
  });

  holdDuration.add(res.timings.duration);

  // Rates
  const ok = (res.status >= 200 && res.status < 300) || res.status === 409;
  okRate.add(ok);
  serverErrorRate.add(res.status >= 500);
  badRequestRate.add(res.status >= 400 && res.status < 500 && res.status !== 409);

  // Counters
  const code = getErrorCode(res) || 'NO_CODE';

  if (res.status >= 200 && res.status < 300) {
    holdSuccess.add(1);

    // 반복 테스트 유지용: 성공한 홀드는 바로 release해서 좌석/락 누적을 막는다.
    if (RELEASE_AFTER) {
      const r2 = http.post(RELEASE_URL, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: 'release' },
      });
      releaseDuration.add(r2.timings.duration);
      check(r2, {
        'release: status is 2xx': (r) => r.status >= 200 && r.status < 300,
      });
    }
  } else if (res.status === 409) {
    holdConflict.add(1, { code });
  } else if (res.status === 404) {
    holdNotFound.add(1, { code });
  } else if (res.status >= 400 && res.status < 500) {
    holdBadRequest.add(1, { code });
  } else if (res.status >= 500) {
    holdServerError.add(1, { code });
    // 콘솔 스팸 방지: 초반 몇 건만 출력
    if (exec.scenario.iterationInTest < 20) {
      console.error(`5xx status=${res.status} code=${code} body=${res.body}`);
    }
  } else {
    holdOther.add(1, { code });
  }

  check(res, {
    'hold: status is 2xx or 409': (r) => (r.status >= 200 && r.status < 300) || r.status === 409,
  });

  sleep(THINK_TIME);
}
