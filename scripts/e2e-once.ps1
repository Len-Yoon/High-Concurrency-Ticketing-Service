$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$base = "http://127.0.0.1:8080"
$userId = 1001
$amount = 100000

function Get-ErrBody($ex){
  try {
    if ($ex.Exception.Response) {
      $sr = New-Object System.IO.StreamReader($ex.Exception.Response.GetResponseStream())
      return $sr.ReadToEnd()
    }
  } catch {}
  return $ex.Exception.Message
}

function Db-Exec([string]$sql){
  $null = docker compose exec -T -e MYSQL_PWD=root mysql mysql -uroot -D ticketing -e $sql
  if ($LASTEXITCODE -ne 0) { throw "DB 실행 실패" }
}

function Db-One([string]$sql){
  $raw = docker compose exec -T -e MYSQL_PWD=root mysql mysql -N -uroot -D ticketing -e $sql
  if ($LASTEXITCODE -ne 0) { throw "DB 조회 실패" }
  if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
  $line = (($raw -split "`r?`n") | Where-Object { $_ -match '\S' } | Select-Object -Last 1)
  if (-not $line) { return $null }
  return $line.Trim()
}

function Invoke-JsonPost([string]$uri, [string]$jsonBody){
  $resp = Invoke-WebRequest -Method Post -Uri $uri -ContentType "application/json; charset=utf-8" -Body $jsonBody -TimeoutSec 15
  if (-not [string]::IsNullOrWhiteSpace($resp.Content)) {
    try { return ($resp.Content | ConvertFrom-Json) } catch { return $resp.Content }
  }
  return $null
}

Write-Host "[1/8] backend up"
docker compose up -d --build --force-recreate backend | Out-Host

Write-Host "[2/8] health wait (max 300s)"
$up = $false
$last = ""

1..300 | ForEach-Object {
  # 1) 컨테이너 재시작 루프 감지
  $state = docker inspect -f "{{.State.Status}} restart={{.RestartCount}} exit={{.State.ExitCode}}" ticketing-backend
  if ($_ % 15 -eq 0) { Write-Host "waiting... ${_}s / $state / $last" }

  # 2) health 체크는 curl.exe로 (PowerShell Invoke-* 연결끊김 회피)
  $code = (& curl.exe -s -o NUL -w "%{http_code}" "$base/actuator/health" 2>$null)
  if ($code -eq "200") {
    try {
      $json = (& curl.exe -s "$base/actuator/health" | ConvertFrom-Json)
      if ($json.status -eq "UP") { $up = $true; break }
      $last = "HTTP=200 status=$($json.status)"
    } catch {
      $last = "HTTP=200 but json parse fail"
    }
  } else {
    $last = "HTTP=$code"
  }

  Start-Sleep -Seconds 1
}

if (-not $up) {
  Write-Host "health check final fail: $last"
  docker inspect -f "{{.State.Status}} restart={{.RestartCount}} exit={{.State.ExitCode}}" ticketing-backend | Out-Host
  docker compose logs backend --since=10m --tail=300 | Out-Host
  throw "health UP 실패"
}

if (-not $up) {
  Write-Host "health check final fail: $last"
  docker inspect -f "{{.State.Status}} restart={{.RestartCount}} exit={{.State.ExitCode}}" ticketing-backend | Out-Host
  docker compose logs backend --since=10m --tail=300 | Out-Host
  throw "health UP 실패"
}

Write-Host "[3/8] confirmed_seat_guard ensure"
Db-Exec @"
CREATE TABLE IF NOT EXISTS confirmed_seat_guard (
  schedule_id BIGINT NOT NULL,
  seat_no VARCHAR(255) NOT NULL,
  reservation_id BIGINT NOT NULL,
  confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (schedule_id, seat_no),
  UNIQUE KEY uk_confirmed_seat_guard_reservation_id (reservation_id),
  CONSTRAINT fk_confirmed_seat_guard_reservation
    FOREIGN KEY (reservation_id) REFERENCES reservation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"@

Write-Host "[4/8] seed 1건 생성"
$seedRaw = docker compose exec -T -e MYSQL_PWD=root mysql mysql -N -uroot -D ticketing -e "
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
"
if ($LASTEXITCODE -ne 0) { throw "seed SQL 실패" }

$seedLine = (($seedRaw -split "`r?`n") | Where-Object { $_ -match '\S' } | Select-Object -Last 1).Trim()
$parts = $seedLine -split "\s+"
if ($parts.Count -lt 3) { throw "seed 파싱 실패: $seedLine" }

$scheduleId = [int]$parts[0]
$seatId = [int]$parts[1]
$seatNo = $parts[2]
Write-Host "seed => scheduleId=$scheduleId seatId=$seatId seatNo=$seatNo userId=$userId amount=$amount"

Write-Host "[5/8] HOLD"
$holdBody = @{ scheduleId=$scheduleId; seatId=$seatId; userId=$userId } | ConvertTo-Json -Compress
try {
  $hold = Invoke-JsonPost "$base/api/reservations/hold" $holdBody
  if ($hold -is [string]) { Write-Host $hold } else { $hold | ConvertTo-Json -Depth 10 | Write-Host }
} catch {
  Write-Host "HOLD FAIL"
  Write-Host (Get-ErrBody $_)
  docker compose logs backend --since=2m --tail=200 | Out-Host
  throw
}

Write-Host "[6/8] READY"
$readyBody = @{ scheduleId=$scheduleId; seatNo=$seatNo; userId=$userId; amount=$amount } | ConvertTo-Json -Compress
try {
  $ready = Invoke-JsonPost "$base/api/payment/ready" $readyBody
  if ($ready -is [string]) { Write-Host $ready } else { $ready | ConvertTo-Json -Depth 10 | Write-Host }
} catch {
  Write-Host "READY FAIL"
  Write-Host (Get-ErrBody $_)
  docker compose logs backend --since=2m --tail=200 | Out-Host
  throw
}

Write-Host "[7/8] orderNo + MOCK-SUCCESS"
$orderNo = Db-One "SELECT order_no FROM payment_order WHERE user_id=$userId AND schedule_id=$scheduleId AND seat_no='$seatNo' ORDER BY id DESC LIMIT 1;"
if (-not $orderNo) { throw "orderNo 조회 실패" }
Write-Host "orderNo=$orderNo"

$mockBody = @{ orderNo=$orderNo } | ConvertTo-Json -Compress
try {
  $mock = Invoke-JsonPost "$base/api/payment/mock-success" $mockBody
  if ($mock -is [string]) { Write-Host $mock } else { $mock | ConvertTo-Json -Depth 10 | Write-Host }
} catch {
  Write-Host "MOCK FAIL"
  Write-Host (Get-ErrBody $_)
  docker compose logs backend --since=2m --tail=300 | Out-Host
  throw
}

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
