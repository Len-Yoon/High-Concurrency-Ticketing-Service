[CmdletBinding()]
param(
    [ValidateSet("start","check","compile","test","restart","logs","stop","reset","e2e","full")]
    [string]$Task = "start",

    [int]$HealthTimeoutSec = 120,
    [int]$LogTail = 120,

    [switch]$NoFrontend,
    [switch]$WithCompile,
    [switch]$WithTest,
    [switch]$RunE2E,

    [long]$ScheduleId = 1,
    [long]$SeatId = 1,
    [string]$SeatNo = "A1",
    [long]$UserId = 1,
    [int]$Amount = 1000
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$ComposeArgs
    )

    Write-Host ">> docker compose $($ComposeArgs -join ' ')" -ForegroundColor Cyan
    & docker compose @ComposeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose 실패: $($ComposeArgs -join ' ')"
    }
}

function Get-HealthStatus {
    try {
        $res = Invoke-WebRequest -UseBasicParsing "http://localhost:8080/actuator/health" -TimeoutSec 5
        $content = if ($res.Content -is [byte[]]) {
            [System.Text.Encoding]::UTF8.GetString($res.Content)
        } else {
            [string]$res.Content
        }
        $obj = $content | ConvertFrom-Json
        return [string]$obj.status
    } catch {
        return $null
    }
}

function Wait-BackendHealth {
    param([int]$TimeoutSec = 120)

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    Write-Host "백엔드 헬스체크 대기중... (최대 ${TimeoutSec}s)" -ForegroundColor Yellow

    while ((Get-Date) -lt $deadline) {
        $status = Get-HealthStatus
        if ($status -eq "UP") {
            Write-Host "백엔드 상태: UP" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "백엔드 헬스체크 타임아웃 (${TimeoutSec}s)"
}

function Show-Status {
    Invoke-Compose @("ps")
    $status = Get-HealthStatus
    if ($status) {
        Write-Host "actuator/health.status = $status"
    } else {
        Write-Host "actuator/health 응답 없음" -ForegroundColor Yellow
    }
}

function Show-Logs {
    param([int]$Tail = 120)
    if ($NoFrontend) {
        Invoke-Compose @("logs","--tail=$Tail","backend")
    } else {
        Invoke-Compose @("logs","--tail=$Tail","backend","frontend")
    }
}

function Start-Stack {
    # 인프라 + 백엔드
    Invoke-Compose @("up","-d","mysql","redis","redpanda","backend")

    if (-not $NoFrontend) {
        Invoke-Compose @("up","-d","frontend")
    }

    Wait-BackendHealth -TimeoutSec $HealthTimeoutSec
    Show-Status
}

function Compile-Backend {
    $g = [guid]::NewGuid().ToString("N")
    Invoke-Compose @(
        "run","--rm",
        "-e","GRADLE_USER_HOME=/tmp/gradle-$g",
        "backend",
        "bash","-lc","./gradlew clean compileJava --no-daemon"
    )
}

function Test-Backend {
    $g = [guid]::NewGuid().ToString("N")
    Invoke-Compose @(
        "run","--rm",
        "-e","GRADLE_USER_HOME=/tmp/gradle-$g",
        "backend",
        "bash","-lc","./gradlew test --no-daemon"
    )
}

function Restart-App {
    if ($NoFrontend) {
        Invoke-Compose @("restart","backend")
    } else {
        Invoke-Compose @("restart","backend","frontend")
    }
    Wait-BackendHealth -TimeoutSec $HealthTimeoutSec
}

function Run-E2EOnce {
    $e2eScript = Join-Path $PSScriptRoot "e2e-once.ps1"
    if (-not (Test-Path $e2eScript)) {
        throw "e2e 스크립트 없음: $e2eScript"
    }

    Write-Host "E2E 실행: scheduleId=$ScheduleId, seatId=$SeatId, seatNo=$SeatNo, userId=$UserId, amount=$Amount" -ForegroundColor Yellow
    & $e2eScript -ScheduleId $ScheduleId -SeatId $SeatId -SeatNo $SeatNo -UserId $UserId -Amount $Amount
}

try {
    switch ($Task) {
        "start" {
            Start-Stack
            Show-Logs -Tail $LogTail
        }
        "check" {
            Show-Status
            Show-Logs -Tail $LogTail
        }
        "compile" {
            Compile-Backend
        }
        "test" {
            Test-Backend
        }
        "restart" {
            Restart-App
            Show-Logs -Tail $LogTail
        }
        "logs" {
            Show-Logs -Tail $LogTail
        }
        "stop" {
            Invoke-Compose @("down")
        }
        "reset" {
            Invoke-Compose @("down","-v")
        }
        "e2e" {
            Run-E2EOnce
        }
        "full" {
            Start-Stack

            if ($WithCompile) { Compile-Backend }
            if ($WithTest)    { Test-Backend }
            if ($RunE2E)      { Run-E2EOnce }

            Restart-App
            Show-Status
            Show-Logs -Tail $LogTail
        }
    }

    Write-Host "완료: Task=$Task" -ForegroundColor Green
}
catch {
    Write-Host "실패: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
