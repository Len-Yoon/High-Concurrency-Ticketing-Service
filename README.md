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
예를 들어 다음 상황에서는 Redis만으로 부족할 수 있습니다. <br>
* Redis 장애
* 네트워크 지연
* TTL 만료 시점 경쟁
* Consumer 재처리
* 서버 재시작
* 동일 이벤트 중복 처리
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
이미 같으 ㄴ 좌석이 확정된 경우 Primary Key 충돌이 발생합니다. <br><br>

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

