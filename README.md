# 🎟 High-Concurrency Ticketing Service

> Redis Queue + Kafka + MySQL 기반 **고동시성 티켓팅 시스템**

대규모 동시 접속 환경에서 발생하는 **좌석 경쟁(Race Condition)** 과  
**트래픽 폭증 문제를 해결하기 위해 설계된 티켓팅 서비스입니다.**

단순 CRUD 프로젝트가 아니라 실제 서비스 수준의:

- 대기열 제어
- 동시성 제어
- 이벤트 기반 처리
- 최종 정합성 보장
- 부하 테스트 검증

까지 포함된 **Production-style Backend 프로젝트**입니다.

---

# 🎯 Problem Definition
**대규모 티켓팅 시스템에서는 다음 문제가 발생합니다.**
- 수천명의 사용자가 동시에 좌석 클릭
- 동일 좌석 중복 예약 가능성
- 트래픽 폭증으로 인한 서버 과부하
- 선착순 공정성 문제

**이 프로젝트는 위 문제들을 해결하기 위해 설계되었습니다.**

---

# 🧱 Architecture

<details>
<summary>Architecture Diagram</summary>

```text
Client
  │
  ▼
Spring Boot API
  │
  ├── MySQL
  │     ├ reservation
  │     ├ payment_order
  │     └ confirmed_seat_guard
  │
  ├── Redis
  │     ├ waiting queue (ZSET)
  │     ├ pass token
  │     └ seat lock
  │
  └── Kafka (Redpanda)
        └ reservation confirm events
```
</details>

---

# 🧰 Tech Stack

<details>
  <summary>Backend</summary>
  
- Java 17
- Spring Boot 3.x
- Spring Data JPA
- Spring Data Redis
- Spring Kafka
</details>

<details>
  <summary>Datastore</summary>
  
- MySQL 8
- Redis 7
</details>

<details>
  <summary>Messaging</summary>
  
- Kafka (Redpanda)
</details>

<details>
<summary>Observability</summary>
  
- Prometheus
- Grafana
- Spring Boot Actuator
</details>

<details>
  <summary>Load Test</summary>
  
- k6
</details>

<details>
  <summary>Deployment</summary>
  
- Docker
- docker-compose
</details>

---

# 🔒 Concurrency Design
본 시스템은 **3단계 동시성 제어 구조**를 사용합니다.

```text
Queue Control
    ↓
Seat Lock
    ↓
DB Final Guard
```

<details>
 <summary>1️⃣ Queue Control (Redis)</summary>

<br>
  
대기열 시스템은 Redis ZSET 기반으로 구현되었습니다.
```text
queue:{scheduleId}
```
#### 동작 흐름
```text
Queue Enter
   │
   ▼
Queue Status Polling
   │
   ▼
PASS Token 발급
   │
   ▼
Seat Hold 가능
```

#### Redis Keys
```text
queue:{scheduleId}

queue:pass:z:{scheduleId}

queue:pass:{scheduleId}:{userId}
```

#### 특징
- FIFO 순서 보장
- Capacity 기반 입장 제어
- PASS TTL 자동 만료
- PASS 자동 재분배

#### 설계 결정
Immediate PASS 발급 방식 대신


**Scheduled Queue Advancement 방식**을 선택했습니다.

이 방식은

- Burst 트래픽 안정화
- Capacity 제어 가능
- TTL 재분배 가능
- Queue 공정성 유지
  
라는 장점이 있습니다.
</details>

<details>
  <summary>2️⃣ Seat Lock (Redis Distributed Lock)</summary>

<br>
  
Redis Lock만으로는 완전한 정합성을 보장할 수 없습니다.

```text
seat:lock:{scheduleId}:{seatNo}
```

#### 특징
- SET NX EX 기반 Atomic Lock
- TTL 자동 해제
- Lua Script Owner 검증
- 동시 클릭 시 1명만 성공

#### 역할
- 동일 좌석 동시 접근 방지
- 빠른 실패 응답 제공
- DB 부하 감소

</details>

<details>
  <summary>3️⃣ DB Final Guard</summary>

<br>


Redis Lock만으로는 완전한 정합성을 보장할 수 없습니다.

```text
네트워크 장애

Kafka 재처리

Consumer 재시작

Redis 장애
```

따라서 DB Primary Key 기반 최종 방어막을 구성했습니다.

#### Table
```text
confirmed_seat_guard
```
</details>








