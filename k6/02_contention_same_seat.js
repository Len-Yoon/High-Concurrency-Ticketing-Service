import http from "k6/http";
http.setResponseCallback(http.expectedStatuses(200, 409, 400, 404));
import { check } from "k6";
import { Counter } from "k6/metrics";

export const lockConflict = new Counter("seat_lock_conflict");
export const badRequest = new Counter("bad_request");
export const notFound = new Counter("not_found");

export const options = {
    vus: 80,
    duration: "30s",
    thresholds: {
        http_req_duration: ["p(95)<800"],
        http_req_failed: ["rate<0.01"], // 진짜 실패율을 보자 (expectedStatuses 밖이면 실패로 잡힘)
    },
};

const BASE = __ENV.BASE_URL || "http://127.0.0.1:8080";

// 여기 2개는 위에서 DB로 확인한 값 주입
const scheduleId = Number(__ENV.SCHEDULE_ID || "2");
const seatId = Number(__ENV.SEAT_ID || "2");

export default function () {
    const userId = __VU * 100000 + __ITER + 1;

    const res = http.post(
        `${BASE}/api/reservations/hold`,
        JSON.stringify({ scheduleId, seatId, userId }),
        {
            headers: {
                "Content-Type": "application/json",
                // 서버가 진짜 이 헤더를 지원할 때만 의미 있음
                "X-LOADTEST-BYPASS": "true",
            },
        }
    );

    const ok = res.status === 200;
    const conflict = res.status === 409;

    // 디버깅용 카운터
    if (res.status === 400) badRequest.add(1);
    if (res.status === 404) notFound.add(1);
    if (conflict) lockConflict.add(1);

    check(res, { "hold is 200 or 409": () => ok || conflict });
}