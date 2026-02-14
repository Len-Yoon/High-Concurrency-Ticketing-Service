param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MetricsUrl = "",
    [int]$ScheduleId = 1,
    [int]$SeatId = 1,
    [string]$UserId = "len-e2e-user",
    [bool]$BypassQueue = $true,
    [string]$QueueToken = "",
    [string]$HoldPath = "/api/reservations/hold",
    [ValidateSet("mockPayment","directConfirm")]
    [string]$ConfirmMode = "mockPayment",
    [string]$ConfirmPath = "/api/payments/mock/success",
    [int]$WaitSeconds = 8
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($MetricsUrl)) {
    $MetricsUrl = "$BaseUrl/actuator/prometheus"
}

function Get-PromSnapshot {
    param([string]$Url)

    $raw = (Invoke-WebRequest -Uri $Url -UseBasicParsing).Content
    $lines = $raw -split "`n" | ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith("#") }

    # outbox/confirm 관련 라인만 추출 (패널/검증 대상 포함)
    $targets = $lines | Where-Object {
        $_ -match "(?i)(outbox|confirm|published|retry|failed|skip)"
    }

    $map = @{}
    foreach ($line in $targets) {
        # metric{labels} value 또는 metric value 포맷 파싱
        if ($line -match '^([a-zA-Z_:][a-zA-Z0-9_:]*(\{.*?\})?)\s+([-+]?\d+(\.\d+)?([eE][-+]?\d+)?)$') {
            $key = $Matches[1]
            $val = [double]$Matches[3]
            $map[$key] = $val
        }
    }
    return $map
}

function Show-Diff {
    param(
        [hashtable]$Before,
        [hashtable]$After
    )

    $keys = @($Before.Keys + $After.Keys | Sort-Object -Unique)
    $rows = @()

    foreach ($k in $keys) {
        $b = if ($Before.ContainsKey($k)) { [double]$Before[$k] } else { 0.0 }
        $a = if ($After.ContainsKey($k)) { [double]$After[$k] } else { 0.0 }
        $d = $a - $b
        if ([math]::Abs($d) -gt 0) {
            $rows += [pscustomobject]@{
                Metric = $k
                Before = $b
                After  = $a
                Delta  = $d
            }
        }
    }

    if ($rows.Count -eq 0) {
        Write-Host "⚠ 관련 메트릭 변화가 감지되지 않았습니다."
    } else {
        $rows | Sort-Object Metric | Format-Table -AutoSize
    }
}

function Get-ReservationId {
    param([object]$Resp)

    $candidates = @(
        $Resp.reservationId,
        $Resp.id,
        $Resp.data.reservationId,
        $Resp.data.id,
        $Resp.result.reservationId,
        $Resp.result.id
    ) | Where-Object { $null -ne $_ -and "$_" -ne "" }

    if ($candidates.Count -gt 0) {
        return "$($candidates[0])"
    }
    return $null
}

Write-Host "=== BASELINE METRICS ==="
$baseline = Get-PromSnapshot -Url $MetricsUrl
Write-Host "baseline entries: $($baseline.Count)"

# 1) HOLD
$holdHeaders = @{
    "Content-Type" = "application/json"
}
if (-not [string]::IsNullOrWhiteSpace($QueueToken)) {
    $holdHeaders["X-QUEUE-TOKEN"] = $QueueToken
}

$holdBody = @{
    scheduleId = $ScheduleId
    seatId     = $SeatId
    userId     = $UserId
    bypassQueue= $BypassQueue
}
if (-not [string]::IsNullOrWhiteSpace($QueueToken)) {
    $holdBody["queueToken"] = $QueueToken
}

Write-Host "=== HOLD REQUEST ==="
$holdResp = Invoke-RestMethod -Method Post -Uri "$BaseUrl$HoldPath" -Headers $holdHeaders -Body ($holdBody | ConvertTo-Json -Depth 10)
$reservationId = Get-ReservationId -Resp $holdResp

if (-not $reservationId) {
    Write-Host "HOLD 응답(JSON):"
    $holdResp | ConvertTo-Json -Depth 10
    throw "reservationId 추출 실패"
}
Write-Host "HOLD OK: reservationId=$reservationId"

# reservationId 숫자형/문자열 모두 대응
$ridValue = $reservationId
[int64]$ridNum = 0
if ([int64]::TryParse("$reservationId", [ref]$ridNum)) {
    $ridValue = $ridNum
}

# 2) CONFIRM
Write-Host "=== CONFIRM REQUEST ($ConfirmMode) ==="
switch ($ConfirmMode) {
    "mockPayment" {
        $confirmBody = @{
            reservationId = $ridValue
            userId        = $UserId
            orderId       = "ORD-$([guid]::NewGuid().ToString('N').Substring(0,12))"
            paymentKey    = "PAY-$([guid]::NewGuid().ToString('N'))"
            amount        = 1000
        }
        $confirmResp = Invoke-RestMethod -Method Post -Uri "$BaseUrl$ConfirmPath" -Headers @{ "Content-Type" = "application/json" } -Body ($confirmBody | ConvertTo-Json -Depth 10)
    }
    "directConfirm" {
        $finalPath = $ConfirmPath.Replace("{reservationId}", "$reservationId")
        try {
            $confirmResp = Invoke-RestMethod -Method Post -Uri "$BaseUrl$finalPath" -Headers @{ "Content-Type" = "application/json" } -Body (@{ userId = $UserId } | ConvertTo-Json -Depth 10)
        } catch {
            # 바디 없는 confirm API 대응
            $confirmResp = Invoke-RestMethod -Method Post -Uri "$BaseUrl$finalPath"
        }
    }
}

Write-Host "CONFIRM 호출 완료"
Start-Sleep -Seconds $WaitSeconds

Write-Host "=== AFTER METRICS ==="
$after = Get-PromSnapshot -Url $MetricsUrl
Write-Host "after entries: $($after.Count)"

Write-Host "=== METRIC DIFF ==="
Show-Diff -Before $baseline -After $after

# 빠른 판정
$totalAbsDelta = 0.0
foreach ($k in ($after.Keys | Sort-Object -Unique)) {
    if ($k -match "(?i)(outbox|confirm|published|retry|failed|skip)") {
        $b = if ($baseline.ContainsKey($k)) { [double]$baseline[$k] } else { 0.0 }
        $a = [double]$after[$k]
        $totalAbsDelta += [math]::Abs($a - $b)
    }
}

if ($totalAbsDelta -gt 0) {
    Write-Host "✅ 관련 메트릭 변화 감지됨 (total abs delta: $totalAbsDelta)"
} else {
    Write-Warning "❌ 메트릭 변화 미감지. Confirm 경로/consumer 처리/대기시간을 점검하세요."
}
