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

#### Constraint
```text
PRIMARY KEY (schedule_id, seat_no)
```

#### 구조
```text
Redis Lock
     +
DB Primary Key Constraint
```

#### 보장사항
- 중복 예약 방지
- Kafka 재처리 안전성 확보
- Race Condition 완전 차단
</details>

---

# 🎬 Reservation Flow
<details>
  <summary>Flow Diagram</summary>

<br>

```text
Queue Enter
   │
   ▼
PASS Token 발급
   │
   ▼
Seat Hold
   │
   ▼
Payment Ready
   │
   ▼
Payment Success
   │
   ▼
Kafka Event
   │
   ▼
Reservation Confirm
```
</details>

--- 

# ⚡ Event Driven Design

<details>
  <summary>Event Driven Diagram</summary>

<br>

예매 확정은 **Kafka 이벤트 기반**으로 처리 됩니다.
```text
Payment Success
      │
      ▼
Kafka Event Publish
      │
      ▼
Reservation Confirm Consumer
```

#### 특징
- Transactional Outbox Pattern
- Idempotent Consumer
- Retry 안전성 확보
</details>

---

# 🗄 Database Design

<details>
  <summary>confirmed_seat_guard Table</summary>

<br>

```SQL
CREATE TABLE confirmed_seat_guard (
  schedule_id BIGINT NOT NULL,
  seat_no VARCHAR(255) NOT NULL,
  reservation_id BIGINT NOT NULL,
  confirmed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (schedule_id, seat_no)
);
```

#### 목적
- Redis Lock 실패 상황에서도 중복 예약 방지
- Kafka 재처리 안전성 확보
- 최종 정합성 보장
</details>

---

# 📊 Load Test

<details>
  <summary>Seat Contention Test</summary>

<br>

#### Seat Contention Test
**동일 좌석을 여러 사용자가 동시에 요청**하는 시나리오 입니다.
```text
Users : 80
Seat : 1
```

#### Result
```text
hold success : 1

hold conflict : 79

checks success : 100%

p95 latency : 9.7ms
```

#### 결과해석
**80명의 사용자**가 동일 좌석을 동시에 요청한 결과
- 정확히 1명만 성공
- 79명 충돌 처리

이는

- Redis Lock 정상 동작
- DB Guard 정상 동작
- 동시성 제어 정상 동작
  
을 의미합니다.
</details>

---

# 🧪 Load Test Run


```Bash
docker run --rm ^
 -e BASE_URL=http://host.docker.internal:8080 ^
 -e SCHEDULE_ID=2 ^
 -e SEAT_ID=2 ^
 -e TOKEN_WAIT_SEC=30 ^
 -v %CD%\k6:/scripts ^
 grafana/k6 run --vus 80 --iterations 80 /scripts/02_contention_same_seat.js
```

--- 

# ⚙️ How to Run?
<details>
  <summary>manual</summary>

#### Start
```Bash
docker compose up -d
```

#### Health Check
```Bash
http://localhost:8080/actuator/health

Expected
{"status":"UP"}
```

</details>

---

# 📈 Performance Summary

- Concurrent Users : 80
- Seat : 1
- Success : 1
- Conflict : 79
- p95 latency : 9.7ms

---

# 🧠 Key Design Decisions

<details>
  <summary>Key Diagram</summary>

### Redis Queue
```text
Redis ZSET 기반 대기열 구현
```

장점
- 순서 보장
- O(logN) 삽입
- TTL 관리 가능

<br>

### DB Guard
```text
(schedule_id, seat_no)
Primary Key Constraint
```

이중 보호 구조
```text
Redis Lock
     +
DB Constraint
```

<br>

### Event Driven Confirm
```text
Kafka 기반 예매 확정 처리
```

특징
- Outbox Pattern
- Idempotent Consumer
- Retry 지원
</details>

---

# 🧪 Test Scenarios

### Seat Contention
```text
k6/02_contention_same_seat.js
```
동일 좌석 경쟁 테스트

<br>

### Queue TTL Redistribution
```text
scripts/queue-ttl-e2e.ps1
```
PASS TTL 만료 후 재분배 검증

---

# 🚢 Deployment
### Docker Environment
<details>
  <summary>Deployment</summary>

- MySQL
- Redis
- Kafka
- Spring Boot
- Prometheus
- Grafana
</details>


---

# 💡 What This Project Demonstrates

<br>

이 프로젝트는 약 **3개월 동안 설계와 구현, 테스트를 반복하며 완성한 고동시성 시스템 프로젝트**입니다.

단순 기능 구현에 그치지 않고 실제 서비스 환경을 가정하여 대기열 시스템, 좌석 동시성 제어, 이벤트 기반 처리 구조, 부하 테스트 검증까지 
단계적으로 구현했습니다.

개발 과정에서 다음과 같은 실제 문제들을 직접 해결하며 시스템을 개선했습니다.

- 고동시성 환경에서 발생하는 Race Condition 문제 해결
- 공정한 순서를 보장하는 Redis 기반 대기열 시스템 설계
- Redis와 DB를 함께 사용하는 다중 방어 구조 설계
- Kafka 기반 이벤트 처리의 멱등성(idempotency) 보장
- k6 부하 테스트를 통한 동시성 문제 재현 및 검증
- 실제 장애 상황을 가정한 디버깅 및 안정화 작업

개발 과정은 쉽지 않았지만, 반복적인 테스트와 개선을 통해 시스템이 안정적으로 동작하도록 만드는 경험을 할 수 있었습니다.

이 프로젝트를 통해 다음과 같은 역량을 실제로 구현하고 검증할 수 있었습니다.

- 고동시성 시스템 설계 경험
- 데이터 정합성 보장을 위한 구조 설계
- 이벤트 기반 아키텍처 구현 경험
- 부하 테스트 기반 성능 검증 경험
- Redis · Kafka · MySQL을 함께 사용하는 백엔드 시스템 구축 경험

이 프로젝트는 단순한 예제 수준을 넘어, **실제 서비스 환경을 가정하고 설계한 Production-style Backend 시스템을 구현한 경험**을 
보여주기 위한 프로젝트입니다.
