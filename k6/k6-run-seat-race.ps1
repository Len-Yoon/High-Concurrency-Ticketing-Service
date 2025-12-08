# k6-run-seat-race.ps1
# 좌석 선점(A1) 경합 테스트 실행 스크립트

# 1) 프로젝트 루트로 이동
cd D:\JavaProjects\High_Concurrency_Ticketing_Service

# 2) k6.exe 위치 자동 탐색 (C:\Program Files, LocalAppData\Programs 밑에서 검색)
$k6 = Get-ChildItem -Path "C:\Program Files","$env:LOCALAPPDATA\Programs" `
    -Recurse -Filter "k6.exe" -ErrorAction SilentlyContinue `
    | Select-Object -First 1

if ($null -eq $k6) {
    Write-Host "k6.exe 를 찾을 수 없습니다. 설치 경로를 확인해주세요." -ForegroundColor Red
    exit 1
}

Write-Host "k6 위치: $($k6.FullName)" -ForegroundColor Cyan

# 3) seat-hold-race.js 실행
& $k6.FullName run .\k6\seat-hold-race.js