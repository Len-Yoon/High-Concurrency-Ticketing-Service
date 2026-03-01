$ErrorActionPreference = "Stop"

# 콘솔 출력 UTF-8 (한글 깨짐 완화)
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$base   = "http://127.0.0.1:8080"   # localhost 말고 127.0.0.1로 고정
$userId = 1001
$amount = 100000

function Db-Exec([string]$sql){
  $null = docker compose exec -T -e MYSQL_PWD=root mysql mysql -uroot -D ticketing -e "$sql"
  if ($LASTEXITCODE -ne 0) { throw "DB 실행 실패" }
}

function Db-One([string]$sql){
  $raw = docker compose exec -T -e MYSQL_PWD=root mysql mysql -N -uroot -D ticketing -e "$sql"
  if ($LASTEXITCODE -ne 0) { throw "DB 조회 실패" }
  if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
  $line = (($raw -split "`r?`n") | Where-Object { $_ -match '\S' } | Select-Object -Last 1)
  if (-not $line) { return $null }
  return $line.Trim()
}

function Curl-GetJson([string]$url, [int]$timeoutSec = 3){
  $out = & curl.exe -sS --max-time $timeoutSec -H "Accept: application/json" -w "`n%{http_code}" "$url"
  $lines = $out -split "`n"
  $code = [int]($lines[-1].Trim())
  $body = ($lines[0..($lines.Length-2)] -join "`n").Trim()
  return @{ code=$code; body=$body }
}

function Curl-PostJson([string]$url, [string]$jsonBody, [int]$timeoutSec = 10){
  # JSON을 인자로 넘기면 PowerShell이 " 를 씹는 경우가 있어서 stdin(@-)으로 전달
  $out = $jsonBody | & curl.exe -sS --max-time $timeoutSec `
    -H "Content-Type: application/json; charset=utf-8" `
    -H "Accept: application/json" `
    -X POST --data-binary '@-' `
    -w "`n%{http_code}" `
    "$url"

  $lines = $out -split "`n"
  $code = [int]($lines[-1].Trim())
  $body = ($lines[0..($lines.Length-2)] -join "`n").Trim()
  return @{ code=$code; body=$body }
}

function Wait-QueuePass([int]$sid, [int]$uid, [int]$maxSec = 30){
  # 1) enter
  $enterBody = @{ scheduleId=$sid; userId=$uid } | ConvertTo-Json -Compress
  $enterResp = Curl-PostJson "$base/api/queue/enter" $enterBody 10
  if ($enterResp.code -lt 200 -or $enterResp.code -ge 300) {
    throw ("QUEUE ENTER FAIL HTTP={0} {1}" -f $enterResp.code, $enterResp.body)
  }

  # enter 응답에 token이 없을 수 있음(enter=대기열 등록만, PASS는 advancer가 발급)
  try { $enterJson = $enterResp.body | ConvertFrom-Json } catch { $enterJson = $null }

  if ($enterJson -and $enterJson.token) {
    Write-Host ("queue enter => token={0}" -f $enterJson.token)
  } else {
    Write-Host ("queue enter => ok (token will be issued later by advancer)")
  }

  # 2) status polling until canEnter=true and token exists
  for ($i=1; $i -le $maxSec; $i++) {
    $st = Curl-GetJson "$base/api/queue/status?scheduleId=$sid&userId=$uid" 5
    if ($st.code -ne 200) {
      if ($i -eq 1 -or ($i % 5 -eq 0)) {
        Write-Host ("queue status wait... {0}s / HTTP={1} {2}" -f $i, $st.code, $st.body)
      }
      Start-Sleep -Seconds 1
      continue
    }

    $stJson = $null
    try { $stJson = $st.body | ConvertFrom-Json } catch { $stJson = $null }

    $canEnter = $false
    $token = $null
    if ($stJson) {
      $canEnter = [bool]$stJson.canEnter
      $token = $stJson.token
    }

    if ($canEnter -and -not [string]::IsNullOrWhiteSpace($token)) {
      Write-Host ("queue pass issued => token={0} expiresAt={1}" -f $token, $stJson.expiresAt)
      return $token
    }

    if ($i -eq 1 -or ($i % 5 -eq 0)) {
      $pos = if ($stJson) { $stJson.position } else { "?" }
      Write-Host ("queue status... {0}s position={1} canEnter={2}" -f $i, $pos, $canEnter)
    }

    Start-Sleep -Seconds 1
  }

  throw ("QUEUE PASS TIMEOUT (>{0}s). Check QueueAdvancer schedules/logs." -f $maxSec)
}

Write-Host "[1/8] infra + backend up"
docker compose up -d mysql redis redpanda backend | Out-Host

Write-Host "[2/8] health wait (max 180s)"
$up = $false
for ($i = 1; $i -le 180; $i++) {
  try {
    $r = Curl-GetJson "$base/actuator/health" 3
    if ($r.code -eq 200 -and $r.body -match '"status"\s*:\s*"UP"') { $up = $true; break }
    if ($i -eq 1 -or ($i % 10 -eq 0)) {
      Write-Host ("waiting... {0}s / HTTP={1} {2}" -f $i, $r.code, $r.body)
    }
  } catch {
    if ($i -eq 1 -or ($i % 10 -eq 0)) {
      Write-Host ("waiting... {0}s / {1}" -f $i, $_.Exception.Message)
    }
  }
  Start-Sleep -Seconds 1
}
if (-not $up) {
  Write-Host "health UP 실패. backend 로그 tail 출력:"
  docker compose logs backend --tail=120 | Out-Host
  throw "health UP 실패"
}

Write-Host "[3/8] confirmed_seat_guard ensure (minimal)"
Db-Exec @"
CREATE TABLE IF NOT EXISTS confirmed_seat_guard (
  schedule_id BIGINT NOT NULL,
  seat_no VARCHAR(32) NOT NULL,
  reservation_id BIGINT NOT NULL,
  confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (schedule_id, seat_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"@

Write-Host "[4/8] seed 1건 생성"
$seedSql = @"
INSERT INTO concert (created_at, description, title)
VALUES (NOW(), 'E2E seed', CONCAT('E2E-', DATE_FORMAT(NOW(), '%Y%m%d%H%i%s')));
SET @cid := LAST_INSERT_ID();

INSERT INTO schedule (created_at, show_at, concert_id)
VALUES (NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), @cid);
SET @sid := LAST_INSERT_ID();

SET @seatNo := CONCAT('Z', DATE_FORMAT(NOW(), '%H%i%s'), LPAD(FLOOR(RAND()*1000),3,'0'));
INSERT INTO seat (created_at, price, seat_no, schedule_id)
VALUES (NOW(), $amount, @seatNo, @sid);
SET @seatId := LAST_INSERT_ID();

SELECT @sid, @seatId, @seatNo;
"@

$seedRaw = docker compose exec -T -e MYSQL_PWD=root mysql mysql -N -uroot -D ticketing -e "$seedSql"
if ($LASTEXITCODE -ne 0) { throw "seed SQL 실패" }

$seedLine = (($seedRaw -split "`r?`n") | Where-Object { $_ -match '\S' } | Select-Object -Last 1).Trim()
$parts = $seedLine -split "\s+"
if ($parts.Count -lt 3) { throw "seed 파싱 실패: $seedLine" }

$scheduleId = [int]$parts[0]
$seatId     = [int]$parts[1]
$seatNo     = $parts[2]
Write-Host "seed => scheduleId=$scheduleId seatId=$seatId seatNo=$seatNo userId=$userId amount=$amount"

Write-Host "[4.5/8] QUEUE enter + wait PASS"
$queueToken = Wait-QueuePass -sid $scheduleId -uid $userId -maxSec 30

Write-Host "[5/8] HOLD (with queueToken)"
$holdBody = @{ scheduleId=$scheduleId; seatId=$seatId; userId=$userId; queueToken=$queueToken } | ConvertTo-Json -Compress
$holdResp = Curl-PostJson "$base/api/reservations/hold" $holdBody 10
if ($holdResp.code -lt 200 -or $holdResp.code -ge 300) {
  throw ("HOLD FAIL HTTP={0} {1}" -f $holdResp.code, $holdResp.body)
}
Write-Host "HOLD OK: $($holdResp.body)"

Write-Host "[6/8] READY"
$readyBody = @{ scheduleId=$scheduleId; seatNo=$seatNo; userId=$userId; amount=$amount } | ConvertTo-Json -Compress
$readyResp = Curl-PostJson "$base/api/payment/ready" $readyBody 10
if ($readyResp.code -lt 200 -or $readyResp.code -ge 300) {
  throw ("READY FAIL HTTP={0} {1}" -f $readyResp.code, $readyResp.body)
}
Write-Host "READY OK: $($readyResp.body)"

Write-Host "[7/8] orderNo + MOCK-SUCCESS"
$orderNo = Db-One "SELECT order_no FROM payment_order WHERE user_id=$userId AND schedule_id=$scheduleId AND seat_no='$seatNo' ORDER BY id DESC LIMIT 1;"
if (-not $orderNo) { throw "orderNo 조회 실패" }
Write-Host "orderNo=$orderNo"

$mockBody = @{ orderNo=$orderNo } | ConvertTo-Json -Compress
$mockResp = Curl-PostJson "$base/api/payment/mock-success" $mockBody 10
if ($mockResp.code -lt 200 -or $mockResp.code -ge 300) {
  throw ("MOCK FAIL HTTP={0} {1}" -f $mockResp.code, $mockResp.body)
}
Write-Host "MOCK OK: $($mockResp.body)"

Write-Host "[8/8] verify"
docker compose exec -T -e MYSQL_PWD=root mysql mysql -N -uroot -D ticketing -e "
SELECT id,order_no,status,fail_reason,updated_at
FROM payment_order
WHERE order_no='$orderNo';

SELECT id,schedule_id,seat_no,user_id,status,active,updated_at
FROM reservation
WHERE user_id=$userId AND schedule_id=$scheduleId AND seat_no='$seatNo'
ORDER BY id DESC LIMIT 1;

SELECT schedule_id,seat_no,reservation_id,confirmed_at
FROM confirmed_seat_guard
WHERE schedule_id=$scheduleId AND seat_no='$seatNo';
" | Out-Host

Write-Host "DONE"