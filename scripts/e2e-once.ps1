$ErrorActionPreference = "Stop"

$base = "http://localhost:8080"
$userId = 1001
$amount = 100000
$healthWaitSec = 180

# 큐를 켠 상태면 토큰 넣어줘야 HOLD 통과됨. (없으면 $null 유지)
$queueToken = $null
# 예: $queueToken = "your-queue-token"

function Get-ErrBody([System.Management.Automation.ErrorRecord]$e) {
  try {
    if ($e.Exception.Response) {
      $sr = New-Object System.IO.StreamReader($e.Exception.Response.GetResponseStream())
      return $sr.ReadToEnd()
    }
  } catch {}
  return $e.Exception.Message
}

function Db-Exec([string]$sql) {
  $null = docker compose exec -T -e MYSQL_PWD=root mysql mysql -uroot -D ticketing -e $sql
  if ($LASTEXITCODE -ne 0) { throw "DB 실행 실패" }
}

function Db-One([string]$sql) {
  $raw = docker compose exec -T -e MYSQL_PWD=root mysql mysql -N -uroot -D ticketing -e $sql
  if ($LASTEXITCODE -ne 0) { throw "DB 조회 실패" }
  if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
  $line = (($raw -split "`r?`n") | Where-Object { $_ -match '\S' } | Select-Object -Last 1)
  if (-not $line) { return $null }
  return $line.Trim()
}

function Extract-OrderNo($readyResp) {
  if ($null -eq $readyResp) { return $null }

  if ($readyResp.PSObject.Properties.Name -contains "orderNo" -and $readyResp.orderNo) {
    return [string]$readyResp.orderNo
  }
  if (($readyResp.PSObject.Properties.Name -contains "data") -and $readyResp.data -and
      ($readyResp.data.PSObject.Properties.Name -contains "orderNo") -and $readyResp.data.orderNo) {
    return [string]$readyResp.data.orderNo
  }
  if (($readyResp.PSObject.Properties.Name -contains "result") -and $readyResp.result -and
      ($readyResp.result.PSObject.Properties.Name -contains "orderNo") -and $readyResp.result.orderNo) {
    return [string]$readyResp.result.orderNo
  }
  return $null
}

try {
  Write-Host "[1/8] infra + backend up"
  docker compose up -d mysql redis redpanda backend | Out-Host

  Write-Host "[2/8] health wait (max ${healthWaitSec}s)"
  $up = $false
  for ($i=1; $i -le $healthWaitSec; $i++) {
    try {
      $h = Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2
      if ($h.status -eq "UP") {
        $up = $true
        Write-Host "health UP (${i}s)"
        break
      }
    } catch {
      if ($i % 5 -eq 0) {
        Write-Host "waiting... ${i}s / $($_.Exception.Message)"
      }
    }
    Start-Sleep -Seconds 1
  }
  if (-not $up) { throw "health UP 실패" }

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

  $seedLine = (($seedRaw -split "`r?`n") | Where-Object { $_ -match '\S' } | Select-Object -Last 1)
  if (-not $seedLine) { throw "seed 결과 없음" }

  $parts = $seedLine.Trim() -split "\s+"
  if ($parts.Count -lt 3) { throw "seed 파싱 실패: $seedLine" }

  $scheduleId = [int64]$parts[0]
  $seatId = [int64]$parts[1]
  $seatNo = [string]$parts[2]

  Write-Host "seed => scheduleId=$scheduleId seatId=$seatId seatNo=$seatNo userId=$userId amount=$amount"

  Write-Host "[5/8] HOLD"
  $holdBody = @{ scheduleId = $scheduleId; seatId = $seatId; userId = $userId } | ConvertTo-Json -Compress
  $holdHeaders = @{}
  if ($queueToken) { $holdHeaders["X-QUEUE-TOKEN"] = $queueToken }

  try {
    $hold = Invoke-RestMethod -Method Post -Uri "$base/api/reservations/hold" `
      -ContentType "application/json; charset=utf-8" -Headers $holdHeaders -Body $holdBody -TimeoutSec 10
    $hold | ConvertTo-Json -Depth 10 | Write-Host
  } catch {
    $err = Get-ErrBody $_
    Write-Host "HOLD FAIL"
    Write-Host $err
    if ($err -like "*QUEUE_NOT_ALLOWED*") {
      throw "QUEUE_NOT_ALLOWED: 큐 토큰(X-QUEUE-TOKEN) 없이 HOLD 호출됨. queueToken 설정하거나 큐 비활성화 필요."
    }
    throw
  }

  Write-Host "[6/8] READY"
  $readyBody = @{ scheduleId = $scheduleId; seatNo = $seatNo; userId = $userId; amount = $amount } | ConvertTo-Json -Compress
  try {
    $ready = Invoke-RestMethod -Method Post -Uri "$base/api/payment/ready" `
      -ContentType "application/json; charset=utf-8" -Body $readyBody -TimeoutSec 10
    $ready | ConvertTo-Json -Depth 10 | Write-Host
  } catch {
    Write-Host "READY FAIL"
    Write-Host (Get-ErrBody $_)
    throw
  }

  Write-Host "[7/8] orderNo + MOCK-SUCCESS"
  $orderNo = Extract-OrderNo $ready
  if (-not $orderNo) {
    $orderNo = Db-One "SELECT order_no FROM payment_order WHERE user_id=$userId AND schedule_id=$scheduleId AND seat_no='$seatNo' ORDER BY id DESC LIMIT 1;"
  }
  if (-not $orderNo) { throw "orderNo 조회 실패" }
  Write-Host "orderNo=$orderNo"

  $mockBody = @{ orderNo = $orderNo } | ConvertTo-Json -Compress
  try {
    $mock = Invoke-RestMethod -Method Post -Uri "$base/api/payment/mock-success" `
      -ContentType "application/json; charset=utf-8" -Body $mockBody -TimeoutSec 10
    $mock | ConvertTo-Json -Depth 10 | Write-Host

    if (($mock.PSObject.Properties.Name -contains "success") -and (-not $mock.success)) {
      throw "mock-success business fail: $($mock.message)"
    }
  } catch {
    Write-Host "MOCK FAIL"
    Write-Host (Get-ErrBody $_)
    throw
  }

  Write-Host "[8/8] verify"
  $verify = docker compose exec -T -e MYSQL_PWD=root mysql mysql -N -uroot -D ticketing -e "
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
"
  $verify | Out-Host

  Write-Host "DONE ✅"
}
catch {
  Write-Host "=== FAILED ==="
  Write-Host $_.Exception.Message
  Write-Host "`n[backend logs tail 200]"
  docker compose logs backend --tail=200 | Out-Host
  throw
}
