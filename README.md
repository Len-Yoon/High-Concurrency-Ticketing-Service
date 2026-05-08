# 🎟 High-Concurrency Ticketing Service

> Redis Queue + Redis Lock + MySQL + Kafka/Outbox 기반 **고동시성 티켓팅 백엔드 프로젝트**

대규모 티켓팅 서비스에서 발생하는 **트래픽 폭증, 대기열 제어, 좌석 중복 선점, 결제 전 좌석 보관, 최종 예매 확정 정합성** 
<br>문제를 직접 설계하고 구현한 백엔드 프로젝트입니다.
<br><br>
단순 CRUD 구현이 아니라, 실제 티켓팅 서비스에서 문제가 되는 동시성 제어와 데이터 정합성을 중심으로 구현했습니다.

---

# 📌 Project Summary


이 프로젝트는 사용자가 공연 회차에 입장하기 위해 대기열에 진입하고, PASS 토큰을 받은 뒤 좌석을 선점하고,<br>
결제를 준비한 후 예매를 확정하는 흐름을 구현합니다.

핵심 목표는 다음과 같습니다.

<details>      
<summary>Project Summary</summary>

<br>

* 순간적으로 몰리는 사용자 요청을 Redis 대기열로 제어
* 동일 좌석에 대한 중복 선점 방지
* 좌석 선점 후 일정 시간 내 결제하지 않으면 자동 만료
* 결제 준비 시 실제 유효한 HOLD 상태인지 검증
* 예매 확정 단계에서 DB 기반 최종 방어막으로 중복 확정 방지
* k6 부하 테스트로 동시성 제어 결과 검증
* Prometheus/Grafana 기반 운영 지표 확인    
</details>

---

# 🎯 Problem Definition
대규모 티켓팅 시스템에서는 다음 문제가 발생합니다.

<details>
<summary>Problem Definition</summary>
      
#### 1. Traffic Burst
예매 오픈 직후 수많은 사용자가 동시에 진입하면 API 서버와 DB에 순간 부하가 집중됩니다.
#### 2. Race Condition
여러 사용자가 같은 좌석을 동시에 클릭하면 동일 좌석에 대해 중복 선점 또는 중복 예약이 발생할 수 있습니다.
#### 3. Fairness
먼저 들어온 사용자가 먼저 입장하지 못하면 선착순 서비스의 공정성이 깨질 수 있습니다.
#### 4. Expiration Handling
좌석을 선점한 사용자가 결제하지 않고 이탈하면 해당 좌석과 입장 권한을 적절히 회수해야 합니다.
#### 5. Final Consistency
Redis Lock만으로는 장애, TTL 만료, 재처리 상황에서 완전한 정합성을 보장하기 어렵기 때문에 DB 레벨의 최종 방어막이 필요합니다.

</details>

---

# 🧱 Architecture

<details>
<summary>Architecture Diagram</summary>

<br>

```text
Client / Frontend
      │
      ▼
Spring Boot API
      │
      ├── Queue API
      │     └── Redis ZSET 기반 waiting queue / pass token
      │
      ├── Reservation API
      │     └── Redis seat lock + reservation hold
      │
      ├── Payment API
      │     └── payment_order 생성 및 mock 결제 성공 처리
      │
      ├── Seat API
      │     └── 좌석 상태 조회 + SSE 실시간 변경 이벤트
      │
      ├── MySQL
      │     ├── concert
      │     ├── schedule
      │     ├── seat
      │     ├── reservation
      │     ├── payment_order
      │     ├── confirmed_seat_guard
      │     └── outbox_event
      │
      ├── Redis
      │     ├── waiting queue
      │     ├── pass token
      │     └── seat lock
      │
      └── Kafka / Redpanda
            └── confirm requested event 처리 구조
```

</details>

---

# 🧰 Tech Stack

<details>
<summary>Tech Stack</summary>

<br>

| Category | Stack |
|:---|:---|
| Language | Java 17 |
| Framework | Spring Boot 3.4.x |
| Persistence | Spring Data JPA, MySQL 8 |
| Cache / Queue / Lock | Redis 7 |
| Messaging | Kafka / Redpanda |
| Observability | Spring Boot Actuator, Prometheus, Grafana |
| Load Test | k6 |
| Frontend | React, Vite, TypeScript |
| Deployment | Docker, docker-compose |

</details>

---

# 🔁 Core Reservation Flow

<details>
<summary>Core Reservation Flow</summary>

#### 1. Queue Enter
   사용자가 특정 공연 회차 대기열에 진입

#### 2. Queue Status Polling
   사용자는 자신의 대기 순번과 입장 가능 여부를 조회

#### 3. PASS Token Issued
   QueueAdvancer가 capacity만큼 PASS 토큰 발급

#### 4. Seat Hold
   PASS 토큰을 가진 사용자만 좌석 선점 가능

#### 5. Payment Ready
   유효한 HOLD 상태인지 검증 후 payment_order 생성

#### 6. Payment Mock Success
   결제 성공을 가정하고 예매 확정 처리

#### 7. Reservation Confirm
   confirmed_seat_guard를 통해 최종 중복 확정 방지

</details>

---

# 🚦Queue System

<details>
<summary>Queue System</summary>

### Purpose
대기열은 예매 오픈 직후 트래픽이 한 번에 서버로 유입되는 것을 막기 위해 구현했습니다. <br>
Redis ZSET을 사용하여 사용자 진입 순서를 기록하고, 별도의 QueueAdvancer가 <br>
일정 주기마다 입장 가능한 사용자에게 PASS 토큰을 발급합니다.

---

### Redis Keys
| Key | Pupose |
|:---|:---|
| queue:{scheduleId} | 대기 중인 사용자 목록 |
| queue:pass:z:{scheduleId} | PASS 발급 사용자와 만료 시각 관리 |
| queue:pass:seq:{scheduleId} | PASS 토큰 sequence |
| queue:advance:lock | QueueAdvancer 중복 실행 방지용 락 |

---

### Queue Enter
사용자가 대기열에 진입하면 queue:{scheduleId} ZSET에 userId를 추가합니다.
```text
member = userId
score  = currentTimeMillis
```
기존에 이미 대기열에 들어간 사용자라면 중복 추가하지 않고 기존 rank를 반환합니다.

---

### Queue Status
사용자는 /api/queue/status를 통해 현재 상태를 조회합니다.
* PASS가 있으면 canEnter=true, token, expiresAt 반환
* PASS가 없으면 현재 대기 순번 반환
* 대기열에도 없으면 자동으로 대기열에 등록

---

### Scheduled Queue Advancement
이 프로젝트는 사용자가 요청할 때마다 즉시 PASS를 발급하는 방식 대신, <br>
**Scheduled Queue Advancement** 방식을 사용합니다. 
<br><bt>
QueueAdvancer가 일정 주기로 실행되며 다음 작업을 수행합니다.

```text
1. queue:advance:lock 획득
2. active scheduleId 스캔
3. 만료된 PASS 제거
4. 현재 PASS 수 확인
5. capacity보다 부족한 만큼 waiting queue에서 사용자 pop
6. PASS 토큰 발급
7. waiting queue에서 제거
```

기본 실행 주기:
```text
ticketing.queue.advance-interval-ms=200
```

</details>

---

# 🔐 Why Scheduled Advancement?

<details>
<summary>Why Scheduled Advancement</summary>

### 좌석 선점은 다음 구조로 처리합니다.
```text
Queue Token Validation
        ↓
Redis Seat Lock
        ↓
Reservation HOLD Insert
        ↓
SSE Seat Event Publish
```
---

### 1. Queue Token Validation
좌석 선점 요청 시 queue 기능이 켜져 있으면 PASS 토큰을 검증합니다. <br>
토큰이 없거나 Redis에 저장된 토큰과 일치하지 않으면 좌석 선점을 허용하지 않습니다.

```text
queue:pass:{scheduleId}:{userId}
```
검증 실패 시 사용자는 대기열로 다시 유도됩니다.

---

### 2. Redis Seat Lock
동일 좌석에 대한 동시 접근을 막기 위해 Redis Lock을 사용합니다.
```text
seat:lock:{scheduleId}:{seatNo}
```
구현 방식:
```text
SET key userId NX EX 300
```
특징: <br>
* NX 옵션으로 이미 선점된 좌석은 중복 선점 불가
* EX 옵션으로 5분 TTL 자동 만료
* release 시 Lua Script로 owner 확인 후 삭제
* 소유자가 아닌 사용자가 다른 사용자의 lock을 해제하지 못하도록 보호

---

### 3. Reservation HOLD
Redis Lock 획득 후 MySQL reservation 테이블에 HOLD 상태를 저장합니다. <br><br>
주요상태:
| Status | Meaning |
|:---|:---|
| HELD | 좌석 선점 중 |
| CONFIRMED | 예매 확정 |
| EXPIRED | 선점 만료 |
| CANCELLED | 사용자 취소 |

<br>
HOLD TTL:

```text
5 minutes
```

---

### 4. Reservation Expired Job
결제하지 않고 만료된 HOLD는 ReservationExpireJob이 주기적으로 정리합니다.
```text
fixedDelay = 5000ms
```
만료 처리 방식:
```sql
UPDATE reservation
   SET status = 'EXPIRED',
       active = 0,
       updated_at = :now
 WHERE status = 'HELD'
   AND active = 1
   AND expires_at < :now
 ORDER BY expires_at
 LIMIT 1000
```
대량 업데이트 시 락 경합과 데드락을 줄이기 위해 batch 방식으로 처리했습니다.

</details>

---

# 🛡 DB Final Guard

<details>
<summary>DB Final Guard</summary>

<br>
Redis Lock은 빠른 선점 제어에는 효과적이지만, 최종 정합성을 완전히 보장하지는 않습니다. <br><br>
예를 들어 다음 상황에서는 Redis만으로 부족할 수 있습니다. <br><br>

* Redis 장애
* 네트워크 지연
* TTL 만료 시점 경쟁
* Consumer 재처리
* 서버 재시작
* 동일 이벤트 중복 처리 <br><br>
따라서 예매 확정 단계에서는 MySQL에 confirmed_seat_guard 테이블을 두고, <br>
(schedule_id, seat_no)를 Primary Key로 사용했습니다.

---

### confirmed_seat_guard
```sql
CREATE TABLE IF NOT EXISTS confirmed_seat_guard (
  schedule_id    BIGINT      NOT NULL,
  seat_no        VARCHAR(32) NOT NULL,
  reservation_id BIGINT      NOT NULL,
  confirmed_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (schedule_id, seat_no)
) ENGINE=InnoDB;
```

---

### Confirm Logic
예매 확정 시 먼저 confirmed_seat_guard에 insert를 시도합니다.
```sql
INSERT INTO confirmed_seat_guard(schedule_id, seat_no, reservation_id)
```
이미 같은 좌석이 확정된 경우 Primary Key 충돌이 발생합니다. <br><br>

이 충돌은 중복 확정 시도로 보고 멱등 성공 처리합니다.
```text
Redis Lock
     +
Reservation Status Check
     +
confirmed_seat_guard PK
```
이를 통해 동일 좌석이 여러 번 확정되는 무제를 최종적으로 방지합니다.

</details>

---

# 💳 Payment Flow
현재 결제는 실제 PG 연동이 아니라 Mock 결제 흐름으로 구현되어 있습니다.

<details>
<summary>Payment Flow</summary>

### Payment Ready
/api/payment/ready 요청 시 다음을 검증합니다.
```text
1. 좌석 존재 여부 확인
2. 해당 사용자에게 유효한 HELD 예약이 있는지 확인
3. DB seat.price 기준으로 결제 금액 확정
4. payment_order 생성
```
결제 금액은 클라이언트 요청값이 아니라 DB의 seat.price를 기준으로 결정합니다.

---

### Payment Mock Success
/api/payment/mock-success 요청 시 결제 성공을 가정하고 예매 확정을 수행합니다.
```text
1. payment_order 조회
2. 이미 PAID 상태면 멱등 성공 처리
3. reservation confirm 수행
4. payment_order 상태를 PAID로 변경
5. confirm 실패 시 payment_order를 CANCELLED 처리
```
mockSuccess()는 Propagation.NOT_SUPPORTED로 처리되어 confirm <br>
실패 후에도 결제 주문 취소 상태를 안전하게 저장할 수 있도록 구성했습니다.

</details>

---

# 📩 Kafka / Outbox Design

<details>
<summary>Kafka / Outbox Design</summary>

<br>

프로젝트에는 Kafka 기반 예매 확정 이벤트 처리 구조가 포함되어 있습니다. <br><br>
단, 현재 코드 기준으로 결제 성공 API는 PaymentService.mockSuccess()에서 <br>
reservationService.confirm()을 직접 호출합니다. <br>
Kafka/Outbox 흐름은 별도의 ConfirmCommandService, OutboxPublisher, <br>
ConfirmRequestedConsumer 구조로 구현되어 있으며, 향후 결제 성공 이벤트 기반 확정 구조로 확장 가능한 형태입니다.

---

### Outbox Event 
outbox_event 테이블은 이벤트 유실을 방지하기 위한 Transactional Outbox Pattern 구조입니다. <br>
주요 컬럼: <br>
| Column | Purpose |
|:---|:---|
| event_id |	이벤트 고유 ID |
| topic | Kafka topic |
| event_key | Kafka message key |
| payload |	JSON payload |
| status | PENDING / PUBLISHED / FAILED |
| retry_count | 재시도 횟수 |
| max_retry | 최대 재시도 횟수 |
| next_retry_at | 다음 재시도 시각 |
| last_error | 마지막 실패 사유 |

---

### Outbox Publisher
OutboxPublisher는 주기적으로 PENDING 이벤트를 조회하고 Kafka로 발행합니다. <br>
```sql
SELECT * FROM outbox_event
 WHERE status = 'PENDING'
   AND next_retry_at <= NOW()
 ORDER BY created_at
 LIMIT :limit
 FOR UPDATE SKIP LOCKED
```
특징: <br>

* FOR UPDATE SKIP LOCKED로 다중 Publisher 환경에서 중복 처리 방지
* Kafka 발행 성공 시 PUBLISHED 처리
* 실패 시 retry_count 증가
* Exponential Backoff 기반 재시도
* 최대 재시도 초과 시 FAILED 처리
* Micrometer 기반 Outbox metric 기록

---

### Confirm Consumer
ConfirmRequestedConsumer는 Kafka topic을 구독합니다. 
```text
ticket.confirm.requested.v1
```
처리 흐름: 
```text
1. JSON payload parsing
2. 필수 필드 검증
3. consumer_dedup 테이블 기반 중복 이벤트 제거
4. stale event 확인
5. 유효 HOLD 상태 확인
6. TicketService.confirmSeat 호출
7. 실패 유형에 따라 skip 또는 retry 처리
```

---

### Idempotency
Consumer는 consumer_dedup 테이블에 event_id를 insert하여 이미 처리한 이벤트를 다시 처리하지 않도록 설계되어 있습니다. 
```sql
INSERT IGNORE INTO consumer_dedup(event_id, processed_at)
VALUES (?, NOW())
```
재시도 가능한 예외가 발생하면 dedup row를 삭제하여 Kafka 재처리가 가능하도록 구성했습니다.

</details>

# 📡 Real-time Seat Status with SSE
좌석 상태 변경은 SSE(Server-Sent Events)를 통해 클라이언트에 전달합니다.

<details>
<summary>Real-time Seat Status with SSE</summary>

<br>

Endpoint: 
```text
GET /api/seats/stream?scheduleId={scheduleId}
```
발행 이벤트:
| Status | Meaning |
|:---|:---|
| HELD | 좌석 선점 중 |
| CONFIRMED | 예매 확정 |
| EXPIRED | 선점 만료 |
| CANCELLED | 사용자 취소 |
프론트엔드는 SSE 이벤트를 수신해 좌석 상태를 즉시 반영하고, 연결 불안정 상황을 대비해 주기적인 refresh도 함께 수행합니다.

</details>

---

# 🗄 Database Design

<details>
<summary>Database Design</summary>   

### Main Tables

| Table | Purpose |
|:---|:---|
| concert | 공연 정보 |
| schedule | 공연 회차 정보 |
| seat | 회차별 좌석 정보 |
| reservation | 좌석 선점/확정 상태 |
| payment_order | 결제 주문 |
| confirmed_seat_guard | 확정 좌석 중복 방지 |
| outbox_event | Kafka 발행 대기 이벤트 |
| consumer_dedup | Kafka Consumer 멱등 처리 |

---

### 1. concert

공연 기본 정보를 저장하는 테이블입니다.

| Column | Type | Description |
|---|---|---|
| id | BIGINT | 공연 ID, Primary Key |
| title | VARCHAR(100) | 공연 제목 |
| description | TEXT | 공연 설명 |
| created_at | DATETIME | 생성 시각 |

역할: <br>
- 공연의 기본 정보를 관리합니다.
- 하나의 공연은 여러 회차(schedule)를 가질 수 있습니다. <br><br>

예시:
```text
concert
- id: 1
- title: Concert A
- description: High traffic ticketing test concert
```

---

### 2. schedule

공연의 실제 회차 정보를 저장하는 테이블입니다.

| Column | Type | Description |
|---|---|---|
| id | BIGINT | 회차 ID, Primary Key |
| concert_id | BIGINT | 공연 ID, concert 참조 |
| show_at | DATETIME | 공연 시작 시각 |
| created_at | DATETIME | 생성 시각 |

역할: <br>
- 특정 공연의 회차 정보를 관리합니다.
- 티켓팅 대기열, 좌석, 예매는 모두 schedule 단위로 동작합니다. <br>

관계: <br>
```text
concert 1 : N schedule
```

하나의 공연은 여러 개의 공연 회차를 가질 수 있습니다.

---

### 3. seat

공연 회차별 좌석 정보를 저장하는 테이블 입니다.

| Column | Type | Description |
|---|---|---|
| id | BIGINT | 좌석 ID, Primary Key |
| schedule_id | BIGINT | 회차 ID, schedule 참조 |
| seat_no | VARCHAR(20) | 좌석 번호 |
| price | INT | 좌석 가격 |
| created_at | DATETIME | 생성 시각 |

제약 조건: 
```text
UNIQUE(schedule_id, seat_no)
```

역할: <br>
- 특정 회차의 좌석 정보를 관리합니다.
- 같은 회차 안에서 동일한 좌석 번호가 중복 생성되지 않도록 제약 조건을 둡니다.
- 결제 금액은 클라이언트 요청값이 아니라 `seat.price`를 기준으로 결정됩니다. <br>

예시:
```text
schedule_id = 1
seat_no = A1
price = 100000
```

---

### 4. reservation

좌석 선점과 예매 확정 상태를 저장하는 핵심 테이블 입니다.

| Column | Type | Description |
|---|---|---|
| id | BIGINT | 예매 ID, Primary Key |
| user_id | BIGINT | 사용자 ID |
| schedule_id | BIGINT | 회차 ID |
| seat_no | VARCHAR(20) | 좌석 번호 |
| status | VARCHAR(20) | 예매 상태 |
| active | INT | 현재 유효 여부 |
| expires_at | DATETIME | 좌석 선점 만료 시각 |
| created_at | DATETIME | 생성 시각 |
| updated_at | DATETIME | 수정 시각 |

상태: 
| Status | Meaning |
|---|---|
| HELD | 좌석 선점 중 |
| CONFIRMED | 예매 확정 |
| EXPIRED | 선점 시간 만료 |
| CANCELLED | 사용자 취소 |

active 값 의미:
| active | Meaning |
|---|---|
| 1 | 현재 유효한 예매 또는 선점 |
| 0 | 만료 또는 취소되어 비활성화된 이력 |

역할: <br>
좌석의 상태 전이를 관리합니다.
```text
HELD
  ↓
CONFIRMED

HELD
  ↓
EXPIRED

HELD
  ↓
CANCELLED
```

주요 설계 포인트: <br>
1. 좌석을 선점하면 테이블에 'HELD' 상태로 저장됩니다.
```text
status = HELD
active = 1
expires_at = now + 5 minutes
```

2. 5분 안에 결제하지 않으면 'ReservationExpireJob'이 해당 row를 만료 처리합니다.
```text
status = EXPIRED
active = 0
```

3. 결제가 성공하면 해당 reservation은 'CONFIRMED' 상태가 됩니다.
```text
status = CONFIRMED
active = 1
```

주요 인덱스: 
```text
idx_reservation_schedule_seat_active
(schedule_id, seat_no, active)

idx_reservation_expire_scan
(status, active, expires_at)
```

인덱스 목적:
| Index | Purpose |
|---|---|
| idx_reservation_schedule_seat_active | 특정 좌석의 현재 점유 상태 조회 |
| idx_reservation_expire_scan | 만료 대상 HELD 예약 빠르게 조회 |

---

### 5. payment_order

결제 준비 및 결제 결과를 저장하는 테이블입니다.

| Column | Type | Description |
|---|---|---|
| id | BIGINT | 결제 주문 ID, Primary Key |
| user_id | BIGINT | 사용자 ID |
| schedule_id | BIGINT | 회차 ID |
| seat_no | VARCHAR(20) | 좌석 번호 |
| amount | INT | 결제 금액 |
| status | VARCHAR(20) | 결제 상태 |
| order_no | VARCHAR(50) | 주문 번호 |
| fail_reason | VARCHAR(255) | 실패 또는 취소 사유 |
| created_at | DATETIME | 생성 시각 |
| updated_at | DATETIME | 수정 시각 |

상태:
| Status | Meaning |
|---|---|
| READY | 결제 준비 완료 |
| PAID | 결제 성공 |
| CANCELLED | 결제 취소 |
| FAILED | 결제 실패 |

제약조건:
```text
UNIQUE(order_no)
```

역할: <br>
- 사용자가 유효한 HOLD 상태를 가진 경우에만 결제 주문을 생성합니다.
- 결제 금액은 DB의 `seat.price`를 기준으로 확정합니다.
- 결제 성공 시 reservation confirm을 수행합니다.
- confirm 실패 시 `payment_order`를 `CANCELLED` 상태로 변경합니다. <br>

결제흐름:
```text
Payment Ready
   ↓
payment_order 생성
   ↓
Mock Payment Success
   ↓
reservation confirm
   ↓
payment_order PAID 처리
```

---

### 6. confirmed_seat_guard

예매 확전 단계에서 동일 좌석이 중복 확정되는 것을 막기 위한 최종 방어 테이블입니다.

| Column | Type | Description |
|---|---|---|
| schedule_id | BIGINT | 회차 ID |
| seat_no | VARCHAR(32) | 좌석 번호 |
| reservation_id | BIGINT | 확정된 reservation ID |
| confirmed_at | DATETIME | 확정 시각 |

기본키:
```text
PRIMARY KEY(schedule_id, seat_no)
```

역할: <br>
- Redis 장애
- 네트워크 지연
- TTL 만료 시점 경쟁
- 서버 재시작
- Kafka Consumer 재처리
- 동일 이벤트 중복 처리 <br><br>

그래서 최종 예매 확정 단계에서 `confirmed_seat_guard`에 먼저 insert를 시도합니다.

```text
INSERT INTO confirmed_seat_guard(schedule_id, seat_no, reservation_id)
```

핵심설계:
```text
Redis Lock
     +
Reservation Status Check
     +
confirmed_seat_guard PK
```

---

### 7. outbox_event

kafka 이벤트 발행의 신뢰성을 높이기 위한 Outbox 테이블입니다.

| Column | Type | Description |
|---|---|---|
| event_id | VARCHAR(64) | 이벤트 ID, Primary Key |
| topic | VARCHAR(120) | Kafka Topic |
| event_key | VARCHAR(120) | Kafka Message Key |
| payload | JSON | 이벤트 Payload |
| status | VARCHAR(20) | Outbox 상태 |
| retry_count | INT | 현재 재시도 횟수 |
| max_retry | INT | 최대 재시도 횟수 |
| next_retry_at | DATETIME | 다음 재시도 시각 |
| published_at | DATETIME | Kafka 발행 완료 시각 |
| last_error | VARCHAR(500) | 마지막 실패 사유 |
| created_at | DATETIME | 생성 시각 |
| updated_at | DATETIME | 수정 시각 |

상태:
| Status | Meaning |
|---|---|
| PENDING | Kafka 발행 대기 |
| PUBLISHED | Kafka 발행 완료 |
| FAILED | 최대 재시도 초과로 실패 |

역할: <br>
DB 상태 변경과 Kafka 메시지 발행을 직접 하나의 트랜잭션으로 묶기는 어렵습니다. <br>
따라서 이벤트를 먼저 DB에 저장하고, 별도 Publisher가 Kafka로 발행합니다.
```text
Business Transaction
   ↓
outbox_event 저장
   ↓
OutboxPublisher 조회
   ↓
Kafka Publish
   ↓
PUBLISHED 처리
```

Publisher 조회 커리:
```sql
SELECT *
  FROM outbox_event
 WHERE status = 'PENDING'
   AND next_retry_at <= NOW()
 ORDER BY created_at
 LIMIT :limit
 FOR UPDATE SKIP LOCKED
```

설계 포인트:
- `FOR UPDATE SKIP LOCKED`로 다중 Publisher 환경에서 중복 처리 방지
- 발행 실패 시 `retry_count` 증가
- Exponential Backoff 방식으로 재시도
- 최대 재시도 초과 시 `FAILED` 처리
- 발행 성공 시 `PUBLISHED` 처리

---

### 8. consumer_dedup
Kafka Consumer의 멱등 처리를 위해 필요한 테이블입니다. <br>

| Column | Type | Description |
|---|---|---|
| event_id | VARCHAR(64) | Kafka Event ID, Primary Key |
| processed_at | DATETIME | 처리 시각 |

권장 DLL:
```sql
CREATE TABLE IF NOT EXISTS consumer_dedup (
  event_id     VARCHAR(64) NOT NULL,
  processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id)
) ENGINE=InnoDB;
```

역할:
Kafka는 장애나 재시도 상황에서 같은 메시지가 다시 전달될 수 있습니다. <br>
Consumer는 이벤트 처리 전에 `consumer_dedup`에 `event_id`를 insert합니다.

```sql
INSERT IGNORE INTO consumer_dedup(event_id, processed_at)
VALUES (?, NOW())
```
이미 존재하는 `event_id`라면 중복 이벤트로 판단하고 처리하지 않습니다.

처리흐름:
```text
Kafka Message Receive
   ↓
consumer_dedup insert
   ↓
insert 성공 → 최초 처리
insert 실패 → 중복 이벤트 skip
```

재시도 가능한 예외가 발생하면 dedup row를 삭제하여 Kafka 재처리가 가능하도록 구성되어 있습니다.

</details>

---

# 🧪 Load Test
부하 테스트는 k6로 작성되어 있습니다.

<details>
<summary>Load Test</summary>

| Script | Purpose | 
|---|---|
| 01_smoke_token_hold_confirm.js | Queue → Hold → Confirm 기본 흐름 확인용 |
| 02_contention_same_seat.js | 동일 좌석 동시 선점 경쟁 테스트 |
| 03_burst_queue_hold.js | 대량 사용자 Queue → PASS → Hold 흐름 테스트 |
| 04_ttl_pressure.js | Queue / TTL 압박 상황 테스트 |

---

### Seat Contention Test
동일 좌석에 80명의 사용자가 동시에 접근하는 시나리오입니다.
```text
Users : 80
Seat  : 1
```

기대결과:
```text
hold success  : 1
hold conflict : 79
```
이 테스트는 다음을 검증합니다. <br>
* Redis Seat Lock이 동일 좌석 동시 접근을 막는지
* 하나의 사용자만 좌석 선점에 성공하는지
* 나머지 사용자가 정상적으로 409 Conflict를 받는지
* 서버 오류가 아니라 정상적인 경쟁 실패로 처리되는지

---

### Queue TTL Redistribution Test
queue-ttl-e2e.ps1은 PASS TTL 만료 후 입장 권한이 다음 사용자에게 재분배되는지 검증합니다. <br><br>
검증흐름:
```text
1. 여러 사용자를 queue에 등록
2. 현재 PASS holder 확인
3. PASS token과 TTL 확인
4. TTL 만료 대기
5. zombie pass 여부 확인
6. 다음 PASS holder 확인
7. 새 token으로 HOLD 성공 여부 확인
```

</details>

---

# 🚀 How to Run

<details>
<summary>How to Run</summary> 

### 1. Start Infrastructure and Application
```text
docker compose up -d
```
실행되는 구성:
```text
MySQL 8.4
Redis 7.2
Redpanda
Spring Boot Backend
React Frontend
```

---

### 2. Health Check
```text
GET http://localhost:8080/actuator/health
```
Expected
```text
{"status":"UP"}
```

---

### 3. Queue Enter
```text
POST /api/queue/enter
Content-Type: application/json

{
  "scheduleId": 1,
  "userId": 1001
}
```

---

### 4. Queue Status
```text
GET /api/queue/status?scheduleId=1&userId=1001
```
PASS 발급 전:
```text
{
  "position": 1,
  "canEnter": false,
  "token": null,
  "expiresAt": null
}
```
PASS 발급 후:
```text
{
  "position": 0,
  "canEnter": true,
  "token": "...",
  "expiresAt": 1760000000000
}
```

---

### 5. Hold Seat
```text
POST /api/reservations/hold
Content-Type: application/json
X-QUEUE-TOKEN: {queueToken}

{
  "scheduleId": 1,
  "seatNo": "A1",
  "userId": 1001,
  "queueToken": "{queueToken}"
}
```
또는 seatId 기반 요청:
```text
{
  "scheduleId": 1,
  "seatId": 1,
  "userId": 1001,
  "queueToken": "{queueToken}"
}
```

--- 

### 6. Payment Ready
```text
POST /api/payment/ready
Content-Type: application/json

{
  "scheduleId": 1,
  "seatNo": "A1",
  "userId": 1001
}
```

---

### 7. Payment Mock Success
```text
POST /api/payment/mock-success
Content-Type: application/json

{
  "orderNo": "PO-..."
}
```

</details>
