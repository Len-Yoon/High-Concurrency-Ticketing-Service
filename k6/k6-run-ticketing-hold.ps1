# 프로젝트 경로는 본인 환경에 맞게 수정
# cd D:\JavaProjects\High_Concurrency_Ticketing_Service

$env:BASE_URL="http://localhost:8080"
$env:SCHEDULE_ID="1"

# ===== 부하 옵션 =====
$env:SCENARIO="constant-vus"   # constant-vus | arrival
$env:VUS="100"
$env:DURATION="30s"

# ===== 대기열(중요) =====
$env:USER_POOL="100"           # allowedRank=100 때문에 기본 100 권장
$env:PRESEED_QUEUE="1"

# ===== 좌석 선택 =====
$env:MODE="random"             # random | race | available
$env:USE_SEAT_LIST="1"         # /api/schedules/{id}/seats에서 seatNo 자동 수집
$env:TARGET_SEAT="A1"          # MODE=race일 때만 사용

# ===== 홀드/릴리즈 =====
$env:HOLD_PATH="/api/tickets/hold"
$env:RELEASE_PATH="/api/tickets/release"
$env:RELEASE_AFTER="1"         # 1이면 hold 성공 후 바로 release해서 상태 고정
$env:SLEEP="0.2"               # 각 VU think time(초)

# ===== 목표 응답시간 (ms) =====
$env:HOLD_P95_MS="800"

k6 run .\k6\ticketing-hold.js
