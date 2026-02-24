<#
.SYNOPSIS
  Queue PASS TTL expiry -> redistribution -> status(token) -> HOLD(200) E2E (PowerShell 5.1)

.DESCRIPTION
  1) (optional) enter a range of users into waiting queue
  2) pick current first PASS holder from Redis passZ
  3) verify status(canEnter/token), read passKey TTL
  4) wait TTL expiry + slack
  5) confirm no zombie (passKey missing AND passZ still contains uid)
  6) pick new current holder and get token via status
  7) try HOLD(200) by random seatId retries (avoid 409)

.NOTES
  - Requires: docker compose, redis service name "redis"
  - Requires: backend exposes GET /api/queue/status?scheduleId=&userId=
  - Intended to run in IntelliJ Terminal (Windows PowerShell 5.1)
#>

[CmdletBinding()]
param(
    [string]$Base = "http://localhost:8080",
    [int]$Sid = 3,

# Users to enqueue (set EnterCount=0 to skip enqueuing)
    [int]$UidStart = 2001,
    [int]$EnterCount = 20,

# Seat range for HOLD retries
    [int]$SeatIdMin = 1,
    [int]$SeatIdMax = 100,
    [int]$MaxHoldTries = 10,

# Polling / timeouts
    [int]$PollMs = 300,
    [int]$MaxWaitPassIssueSec = 20,
    [int]$MaxWaitStatusSec = 30,
    [int]$SlackSecAfterTtl = 7
)

$ErrorActionPreference = "Stop"

# ---- Redis keys ----
$queueZ = "queue:${Sid}"
$passZ  = "queue:pass:z:${Sid}"
function PassKey([int]$uid) { return "queue:pass:${Sid}:${uid}" }

# ---- Helpers ----
function RedisCli {
    param([Parameter(ValueFromRemainingArguments=$true)][object[]]$Args)
    $out = & docker compose exec redis redis-cli @Args 2>$null
    if ($LASTEXITCODE -ne 0) { throw ("redis-cli failed: " + ($Args -join " ")) }
    return ($out | Out-String).Trim()
}

function NilToNull([string]$s) {
    if ([string]::IsNullOrWhiteSpace($s)) { return $null }
    if ($s -eq "(nil)") { return $null }
    return $s
}

function HealthCheck {
    $h = Invoke-RestMethod -Method Get -Uri "$Base/actuator/health"
    return $h.status
}

function EnterUser([int]$uid) {
    Invoke-RestMethod -Method Post -Uri "$Base/api/queue/enter" -ContentType "application/json" `
    -Body (@{ scheduleId=$Sid; userId=$uid } | ConvertTo-Json -Compress) | Out-Null
}

function GetQueueStatusJson([int]$uid) {
    $url = "$Base/api/queue/status?scheduleId=$Sid&userId=$uid"
    return Invoke-RestMethod -Method Get -Uri $url
}

function HoldSeat([int]$uid, [string]$token, [int]$seatId) {
    $payload = @{
        scheduleId  = $Sid
        seatId      = $seatId
        userId      = $uid
        bypassQueue = $false
        queueToken  = $token
    }
    try {
        $res = Invoke-WebRequest -Method Post -Uri "$Base/api/reservations/hold" -ContentType "application/json" `
      -Body ($payload | ConvertTo-Json -Compress) -UseBasicParsing
        return [pscustomobject]@{ StatusCode=[int]$res.StatusCode; Content=$res.Content }
    } catch {
        $r = $_.Exception.Response
        if ($null -eq $r) { throw }
        $status = [int]$r.StatusCode
        $sr = New-Object System.IO.StreamReader($r.GetResponseStream())
        $txt = $sr.ReadToEnd()
        $sr.Close()
        return [pscustomobject]@{ StatusCode=$status; Content=$txt }
    }
}

function PickFirstPassHolder() {
    $uidStr = NilToNull (RedisCli zrange $passZ 0 0)
    if ($null -eq $uidStr) { return $null }
    return [int]$uidStr
}

function WaitPassZNonEmpty() {
    $deadline = (Get-Date).AddSeconds($MaxWaitPassIssueSec)
    do {
        Start-Sleep -Milliseconds $PollMs
        $c = [int](RedisCli zcard $passZ)
    } while ($c -eq 0 -and (Get-Date) -lt $deadline)
    return $c
}

function WaitTokenFromStatus([int]$uid) {
    $deadline = (Get-Date).AddSeconds($MaxWaitStatusSec)
    $token = $null
    $last = $null
    do {
        $last = GetQueueStatusJson $uid
        if ($last.canEnter -eq $true) {
            $t = $last.token
            if ($null -ne $t -and $t.ToString().Length -gt 0) {
                $token = $t.ToString()
                break
            }
        }
        Start-Sleep -Milliseconds $PollMs
    } while ((Get-Date) -lt $deadline)

    return [pscustomobject]@{
        Token = $token
        LastStatus = $last
    }
}

# ---- Run ----
Write-Host "`n== QUEUE TTL E2E ==" -ForegroundColor Cyan
Write-Host ("Base={0} Sid={1}" -f $Base, $Sid) -ForegroundColor DarkGray

Write-Host "`n== HEALTH ==" -ForegroundColor Cyan
$hs = HealthCheck
Write-Host ("health: " + $hs) -ForegroundColor Green
if ($hs -ne "UP") { throw "Backend health is not UP" }

Write-Host "`n== PRE STATE (Redis) ==" -ForegroundColor Cyan
Write-Host ("queueZ zcard=" + (RedisCli zcard $queueZ)) -ForegroundColor DarkGray
Write-Host ("passZ  zcard=" + (RedisCli zcard $passZ))  -ForegroundColor DarkGray

# 1) enter users
if ($EnterCount -gt 0) {
    $uids = $UidStart..($UidStart + $EnterCount - 1)
    Write-Host "`n== ENTER USERS ==" -ForegroundColor Cyan
    foreach ($u in $uids) { EnterUser $u }
    Write-Host ("ENTER DONE users={0} range={1}..{2}" -f $uids.Count, $uids[0], $uids[-1]) -ForegroundColor Green
} else {
    Write-Host "`n== ENTER USERS skipped (EnterCount=0) ==" -ForegroundColor Yellow
}

# 2) wait pass issuance
Write-Host "`n== WAIT PASS ISSUED (passZ zcard > 0) ==" -ForegroundColor Cyan
$passZCard = WaitPassZNonEmpty
Write-Host ("passZ zcard=" + $passZCard) -ForegroundColor Green
if ($passZCard -eq 0) {
    throw "PASS not issued: passZ zcard=0. Check QueueAdvancer enabled/capacity/engine."
}

# 3) pick first PASS holder (before TTL)
$firstUid = PickFirstPassHolder
if ($null -eq $firstUid) { throw "Could not read first PASS holder from passZ." }
$firstKey = PassKey $firstUid

$ttlStr    = RedisCli ttl $firstKey
$existsStr = RedisCli exists $firstKey
$tokenStr  = NilToNull (RedisCli get $firstKey)

Write-Host "`n== FIRST PASS HOLDER (before TTL) ==" -ForegroundColor Cyan
Write-Host ("firstUid=" + $firstUid) -ForegroundColor Green
Write-Host ("passKey=" + $firstKey) -ForegroundColor Green
Write-Host ("ttl=" + $ttlStr + " exists=" + $existsStr) -ForegroundColor Green
$tokPrint = $(if ($null -eq $tokenStr -or $tokenStr -eq "") { "<null>" } else { $tokenStr })
Write-Host ("redisToken=" + $tokPrint) -ForegroundColor Green

if ($existsStr -eq "0") { throw "passKey missing for firstUid. Key format mismatch? key=$firstKey" }
if ($ttlStr -eq "-1")   { throw "passKey has no TTL (ttl=-1). pass-ttl config not applied." }

Write-Host "`n== STATUS(firstUid, before TTL) ==" -ForegroundColor Cyan
$stBefore = GetQueueStatusJson $firstUid
Write-Host ("status = " + ($stBefore | ConvertTo-Json -Compress)) -ForegroundColor DarkCyan

# 4) wait TTL expiry
$waitSec = [int]$ttlStr + $SlackSecAfterTtl
Write-Host "`n== WAIT TTL expiry: ${waitSec}s ==" -ForegroundColor Yellow
Start-Sleep -Seconds $waitSec

# 5) zombie check
$existsAfter = RedisCli exists $firstKey
$ttlAfter    = RedisCli ttl $firstKey
$zscoreAfter = NilToNull (RedisCli zscore $passZ $firstUid)

Write-Host "`n== AFTER TTL CHECK ==" -ForegroundColor Cyan
Write-Host ("passKey exists=" + $existsAfter + " ttl=" + $ttlAfter) -ForegroundColor Green
$zPrint = $(if ($null -eq $zscoreAfter -or $zscoreAfter -eq "") { "<nil>" } else { $zscoreAfter })
Write-Host ("passZ zscore(firstUid)=" + $zPrint) -ForegroundColor Green

if ($existsAfter -eq "0" -and $null -ne $zscoreAfter) {
    Write-Host "`n!! FAIL: ZOMBIE DETECTED (passKey expired but firstUid still in passZ) !!" -ForegroundColor Red
    throw "ZombieDetected"
} else {
    Write-Host "`nOK: no zombie detected (firstUid removed from passZ when passKey expired)" -ForegroundColor Green
}

# 6) show current passZ
Write-Host "`n== passZ NOW (holders) ==" -ForegroundColor Cyan
RedisCli zrange $passZ 0 -1 WITHSCORES

# 7) pick current holder and get token
$currentUid = PickFirstPassHolder
if ($null -eq $currentUid) { throw "passZ is empty. If capacity>0, advancer might not be issuing." }

Write-Host "`n== CURRENT HOLDER (post TTL) ==" -ForegroundColor Cyan
Write-Host ("currentUid=" + $currentUid) -ForegroundColor Green

Write-Host "`n== WAIT canEnter+token via /api/queue/status ==" -ForegroundColor Cyan
$r = WaitTokenFromStatus $currentUid
Write-Host ("status(currentUid) = " + ($r.LastStatus | ConvertTo-Json -Compress)) -ForegroundColor DarkCyan
if ($null -eq $r.Token) {
    throw "TokenNotObserved: status did not return token in time"
}

# 8) HOLD (aim 200) with random seat retries
Write-Host "`n== HOLD TRY (aim 200) ==" -ForegroundColor Cyan
Write-Host ("SeatRange={0}..{1} MaxTries={2}" -f $SeatIdMin, $SeatIdMax, $MaxHoldTries) -ForegroundColor DarkGray

$token = $r.Token
$success = $false

for ($i=1; $i -le $MaxHoldTries; $i++) {
    $seatId = Get-Random -Minimum $SeatIdMin -Maximum ($SeatIdMax + 1)
    Write-Host ("try#{0} seatId={1}" -f $i, $seatId) -ForegroundColor DarkGray

    $holdRes = HoldSeat $currentUid $token $seatId
    Write-Host ("HTTP " + $holdRes.StatusCode) -ForegroundColor Green

    if ($holdRes.StatusCode -eq 200) {
        Write-Host "HOLD SUCCESS (200)" -ForegroundColor Green
        $success = $true
        $holdRes | Format-List
        break
    }

    if ($holdRes.StatusCode -eq 409) {
        # seat already locked -> try another
        continue
    }

    # 400 is meaningful (token expired / queue not allowed)
    if ($holdRes.StatusCode -eq 400) {
        Write-Host "HOLD got 400 (token expired or queue not allowed) -> stop" -ForegroundColor Yellow
        $holdRes | Format-List
        break
    }

    Write-Host "HOLD got unexpected status -> stop" -ForegroundColor Yellow
    $holdRes | Format-List
    break
}

if (-not $success) {
    Write-Host "`nNOTE: did not reach HOLD 200 within MaxHoldTries (seats may be heavily contended/locked)" -ForegroundColor Yellow
}

Write-Host "`n== DONE ==" -ForegroundColor Green