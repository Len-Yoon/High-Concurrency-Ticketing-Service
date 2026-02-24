param(
    [string]$Base = "http://localhost:8080",
    [int]$Sid = 3
)

$ErrorActionPreference="Stop"
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "== Run k6 queue-hold scenarios ==" -ForegroundColor Cyan

# Prefer local k6 if available, else docker
$useDocker = $false
try {
    $v = & k6 version 2>$null
    if (-not $v) { $useDocker = $true }
} catch {
    $useDocker = $true
}

function Set-K6Env {
    param([hashtable]$envs)
    foreach ($k in $envs.Keys) {
        # PS 5.1-safe dynamic env var assignment
        Set-Item -Path ("env:{0}" -f $k) -Value ([string]$envs[$k])
    }
}

function Run-Local {
    param([string]$file, [hashtable]$envs)
    Set-K6Env $envs
    & k6 run $file
}

function Run-Docker {
    param([string]$file, [hashtable]$envs)
    $pwdPath = (Get-Location).Path

    $args = @(
        "run","--rm",
        "-v", ("{0}:/scripts" -f $pwdPath),
        "-w", "/scripts"
    )

    foreach ($k in $envs.Keys) {
        $args += @("-e", ("{0}={1}" -f $k, $envs[$k]))
    }

    $args += @("grafana/k6","run",$file)
    & docker @args
}

# 1) Burst enter->pass->hold
$env1 = @{
    BASE=$Base; SID=$Sid;
    VUS=100; DURATION="30s";
    SEAT_FROM=1; SEAT_TO=100;
    MAX_WAIT_MS=15000; POLL_MS=200
}
Write-Host "`n[1/2] 04_burst_queue_hold.js" -ForegroundColor Yellow
if ($useDocker) { Run-Docker "04_burst_queue_hold.js" $env1 } else { Run-Local "04_burst_queue_hold.js" $env1 }

# 2) TTL pressure
$env2 = @{
    BASE=$Base; SID=$Sid;
    VUS=50; DURATION="90s";
    SEAT_FROM=1; SEAT_TO=100;
    MAX_WAIT_MS=15000; POLL_MS=200;
    STALE_RATIO=0.5; STALE_SLEEP_SEC=75
}
Write-Host "`n[2/2] 05_ttl_pressure.js" -ForegroundColor Yellow
if ($useDocker) { Run-Docker "05_ttl_pressure.js" $env2 } else { Run-Local "05_ttl_pressure.js" $env2 }

Write-Host "`n== DONE ==" -ForegroundColor Green