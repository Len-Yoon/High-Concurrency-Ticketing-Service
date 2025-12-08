# k6 설치 방법 (Windows)

티켓팅 프로젝트 부하 테스트를 위해 사용하는 k6 설치 방법 정리.

---

## 1. 설치 방식 개요

- **OS**: Windows 10 이상
- **설치 도구**: `winget` (Windows 공식 패키지 관리자)
- 설치 후에는 아무 데서나 `k6 run ...` 명령으로 부하 테스트 실행.

---

## 2. winget으로 k6 설치

### 2-1. k6 패키지 검색

1. **PowerShell** 실행
2. 아래 명령 실행:

```powershell
winget search k6
```

3. 아래와 비슷한 출력이 보이면 OK:
    Name   k6
    Id     GrafanaLabs.k6
    Source winget
   - 처음 실행 시 아래처럼 약관 동의가 뜰 수 있음:
    The `msstore` source requires that you view the following agreements before using.
    ...
    Do you agree to all the source agreements terms?
    [Y] Yes  [N] No:
   - Y 입력 후 엔터

### 2-2. k6 설치
```powershell
winget install --id GrafanaLabs.k6 --source winget
```
   - 설치 완료되면
```powershell
k6 version
```