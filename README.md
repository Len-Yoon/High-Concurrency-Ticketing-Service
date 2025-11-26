# 🎟️ High-Concurrency Ticketing Service  
### Redis Queue + Kafka + k6 + Grafana + Docker, 그리고 Java/Spring/JPA 중급 공부용

대규모 동시 접속을 가정한 **콘서트 티켓팅 서비스**입니다.  
단순 CRUD 샘플이 아니라, **실제 티켓팅 도메인 + 고동시성 + 운영/배포까지** 전부 묶어서

> **“중급 백엔드 개발자 이상이 알아야 할 것들을 한 번에 공부하고 검증하기 위한 프로젝트”**

라는 목표로 설계했습니다.

<br><br>

## 1. 🎯 Motivation

<details>

이 프로젝트의 목적은 단순히 “예매 기능 만들었다”가 아니라:

- **Java / Spring 중급 수준 문법과 패턴을 직접 써 보는 것**
- **JPA에 맞는 도메인/DB 설계 경험 쌓기**
- **Redis, Kafka를 이용한 대기열/비동기 처리 설계**
- **k6 + Grafana를 이용한 부하 테스트 & 모니터링**
- **Docker로 패키징해서 개인 서버에 직접 배포까지 해보는 것**

즉, **중급 개발자로 성장하기 위해 필요한 기술 스택 전체를 한 도메인 안에 포함한 학습용 실전 프로젝트**입니다.
</details>

<br><br>

## 2. 📚 Learning Goals (학습 목표)

<details>

### 2.1 ☕ Java (중급 이상)

- 컬렉션/제네릭, 람다/스트림, Optional 활용
- equals/hashCode, 불변 객체, 값 객체(Value Object) 설계
- 예외 계층 설계 (checked/unchecked, 커스텀 예외)
- 간단한 동시성 개념 (쓰레드/Executor 정도의 감)

### 2.2 🌱 Spring / Spring Boot

- IoC/DI, Bean 라이프사이클, 빈 스코프 개념
- 레이어드 아키텍처 (Controller – Service – Repository – Domain)
- Bean Validation (`@Valid`, `@NotNull`, `@Size` 등)
- 글로벌 예외 처리 (`@ControllerAdvice`, `@ExceptionHandler`)
- 트랜잭션 관리 (`@Transactional`, readOnly, 예외 시 롤백 규칙)
- 설정 관리 (`application.yml`, 프로필, `@ConfigurationProperties`)
- 로깅 설계 (SLF4J + logback, 공통 로깅 필터)

### 2.3 🧩 JPA / DB

- 도메인 중심으로 다시 짜는 엔티티/연관관계 설계
- 단방향 연관관계 위주 설계, 연관관계의 주인 개념 이해
- 값 타입 (`@Embeddable`, `@Embedded`) 활용
- JPQL, fetch join, N+1 문제와 성능 튜닝 포인트
- `@Version` / 비관적 락, DB Unique 제약 + 예외 처리
- “DB가 최종 진실(SSOT)” 역할을 하는 구조 설계

### 2.4 🛠 Infra / 운영

- Redis: 대기열(Waiting Room), 좌석 선점(Seat Lock)
- Kafka: 결제 완료 이벤트 → 비동기 예매 확정
- k6: API 부하 테스트 (RPS / 응답시간 / 에러율 측정)
- Prometheus + Grafana: 메트릭 수집 / 대시보드 구성
- Docker + docker-compose: 전체 스택 컨테이너화
- 개인 서버(예: Ubuntu)에 배포 경험
</details>

<br><br>

## 3. 🧱 Tech Stack

<details>

**Backend**

- Java 17
- Spring Boot 3.x
  - Spring Web
  - Spring Data JPA
  - Spring Data Redis
  - Spring for Apache Kafka
  - Spring Boot Actuator
  - Bean Validation

**Datastore & Messaging**

- MySQL 
- Redis
- Apache Kafka

**Testing & Monitoring**

- JUnit / Spring Boot Test (단위/통합 테스트)
- k6 (부하 테스트)
- Prometheus
- Grafana

**Packaging & Deployment**

- Docker
- docker-compose
- Personal Server (Ubuntu 등)

</details>


<br><br>

## 4. 🏗 Architecture

<details>

### 4.1 전체 아키텍처 한 줄 요약

> **Spring Boot 기반 모놀리식 애플리케이션**에  
> **레이어드 아키텍처(Controller / Application / Domain / Infra)**를 적용하고,  
> **JPA 중심 도메인 설계에 Redis(대기열/좌석 락)와 Kafka(이벤트 기반 예매 확정)를 결합한,  
> 이벤트 드리븐 고동시성 티켓팅 서비스 아키텍처**

### 4.2 🧬 Layered + DDD-ish 구조

- `api`  
  - REST Controller  
  - Request/Response DTO  
  - Validation, 인증/인가(추후)  

- `application`  
  - 유스케이스 서비스 (대기열 진입, 좌석 홀드, 결제 플로우, 예매 확정 등)  
  - 트랜잭션 경계 관리 (`@Transactional`)  

- `domain`  
  - 엔티티 (`User`, `Concert`, `Schedule`, `Seat`, `Reservation`, `PaymentOrder` 등)  
  - 도메인 규칙 및 도메인 서비스  

- `infra`  
  - JPA Repository  
  - Redis 어댑터 (대기열/좌석 선점)  
  - Kafka Producer/Consumer 어댑터  

**의존성 방향**

```text
api → application → domain → infra
컨트롤러는 Redis/Kafka/JPA 같은 기술 세부사항에 직접 의존하지 않고,
항상 유스케이스 서비스(application)만 바라보도록 설계합니다.
```

### 4.3 🚢 배포 관점

- 단일 Spring Boot 모놀리식 앱
- Docker로 이미지 빌드
- docker-compose로 MySQL, Redis, Kafka, Prometheus, Grafana와 함께 실행
- 개인 서버(Ubuntu)에 동일한 compose로 배포

</details>


<br><br>

## 5. 🎭 Domain & Features

<details>

### 5.1 Domain Model (요약)
- `User` : 사용자
- `Concert` : 공연 정보
- `Schedule` : 공연 회차 (공연 + 날짜/시간)
- `Seat` : 회차별 좌석 (좌석 번호, 가격 등)
- `Reservation` : 최종 예매 (누가, 어떤 회차, 어떤 좌석)
- `PaymentOrder` : 결제 주문 상태 관리 (READY / PAID / CANCELLED 등)

### JPA 설계 포인트
- 다대다 지양, 항상 중간 엔티티 사용
- 연관관계는 단방향 ManyToOne 중심
- 값 타입(예: 금액, 좌석 위치 등)을 @Embeddable로 분리할 계획

### 5.2 주요기능

#### 1) 📃 공연/좌석 조회
- `GET /concerts` : 공연 목록 조회
- `GET /concerts/{id}/schedules` : 공연 회차 조회
- `GET /schedules/{id}/seats` : 회차별 좌석 목록 조회 (fetch join으로 N+1 방지)

#### 2) ⏳ 입장 대기열 (Waiting Room, Redis ZSET)
- `ZSET queue:{scheduleId}`
  - score: 대기열 진입 시간 또는 증가 시퀀스
  - value: userId

#### API 
- `POST /queue/enter` : 대기열 진입, 현재 순번 반환
- `GET /queue/status?userId=&scheduleId=` : 현재 순번/입장 가능 여부 조회
→ 상위 N명만 실제 예매 플로우 접근 허용

#### 3) 좌석 선점 (Seat Hold, Redis)
- 키: `seat:lock:{scheduleId}:{seatNo}`
- 값: `userId`
- TTL: 5분 (설정값)

#### API
- `POST /tickets/hold` : 좌석 선점 (SET NX EX)
- `POST /tickets/release` : 좌석 선점 해제
→ 좌석 두 명이 동시에 클릭해도, Redis 원자 연산으로 1명만 성공

#### 4) 💳 결제 & 비동기 예매 확정 (Kafka + JPA)
- `POST /payment/ready`
  - 좌석 홀드 여부 검증
  - `PaymentOrder` 생성 (status = READY)
  - 실제 PG는 목업으로 시작 (나중에 연동 가능하게 구조만 잡아둠)

- `POST /payment/mock-success`
  - 결제 성공 이벤트를 대신하는 엔드포인트
  - Kafka로 `PaymentCompletedEvent` 발행

- `ReservationConfirmConsumer` (Kafka Consumer)
  - 이벤트 수신 → JPA 트랜잭션으로 예매 확정
  - `Reservation` 테이블에 insert 시도
  - **DB Unique 제약** (`schedule_id`, `seat_no`) 으로 중복 예매 방지
  - 성공 시 `PaymentOrder.status = PAID`, 실패 시 `CANCELLED`

</details>

<br><br>

## 🗄 DB Schema (Draft Overview)
⚠️ 아래 스키마는 **초기 설계 초안**이며, 실제 구현 과정에서 변경될 수 있습니다.
⚠️ README 상의 SQL 예시는 **핵심 제약 설명용 포인트**이며, 전체 스키마의 일부입니다.

<details>
  
```sql
 -- 사용자
CREATE TABLE user_account (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(100) NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_email (email)
);

-- 공연
CREATE TABLE concert (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(100) NOT NULL,
    description TEXT         NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 공연 회차
CREATE TABLE schedule (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    concert_id  BIGINT       NOT NULL,
    show_at     DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_schedule_concert
        FOREIGN KEY (concert_id) REFERENCES concert (id)
);

-- 회차별 좌석 정보
CREATE TABLE seat (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT       NOT NULL,
    seat_no     VARCHAR(20)  NOT NULL,
    price       INT          NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_seat_schedule_seat (schedule_id, seat_no),
    CONSTRAINT fk_seat_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedule (id)
);

-- 최종 예매
CREATE TABLE reservation (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    schedule_id BIGINT       NOT NULL,
    seat_no     VARCHAR(20)  NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reservation_schedule_seat (schedule_id, seat_no),
    CONSTRAINT fk_reservation_user
        FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_reservation_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedule (id)
);

-- 결제 주문 (PG 연동용)
CREATE TABLE payment_order (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    schedule_id BIGINT       NOT NULL,
    seat_no     VARCHAR(20)  NOT NULL,
    amount      INT          NOT NULL,
    status      VARCHAR(20)  NOT NULL,  -- READY / PAID / CANCELLED / FAILED ...
    pg_order_id VARCHAR(100) NOT NULL,  -- 우리 시스템 주문 번호
    pg_tx_id    VARCHAR(100) NULL,      -- PG 트랜잭션 ID
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NULL,
    UNIQUE KEY uk_payment_pg_order (pg_order_id),
    UNIQUE KEY uk_payment_pg_tx    (pg_tx_id),
    CONSTRAINT fk_payment_user
        FOREIGN KEY (user_id)    REFERENCES user_account (id),
    CONSTRAINT fk_payment_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedule (id)
);
```

#### 핵심 포인트
- `seat`와 `reservation` 둘 다 `(schedule_id, seat_no)` 기준으로 Unique 제약
- 고동시성 상황에서 중복 예매 방지를 위해 `reservation.uk_reservation_schedule_seat` 를 최종 방어선으로 사용
</details>

<br><br>

## 7. ⚙️ JPA & DB 설계 포인트 (요약)

<details>

### 7.1 예매 중복 방지 설계
- 애플리케이션 레벨에서 중복 체크
- DB 레벨에서 Unique 제약으로 최종 방어
- 동시에 여러 이벤트가 들어와도 1명만 성공, 나머지는 `DataIntegrityViolationException`을 통해 안전하게 실패 처리

### 7.2 JPA 설계 원칙
- 단방향 연관관계 먼저, 양방향은 정말 필요한 곳에서만
- LAZY Fetch 기본
- 조회 전용 복잡 쿼리는 JPQL/QueryDSL로 별도 조회용 메서드 구성
- 도메인 규칙은 가능한 한 도메인 계층 안에 표현
</details>

<br><br>

## 8. 🧪 Testing Strategy
이 프로젝트는 **풀 TDD 프로젝트는 아닙니다.**
대신 목적이 분명한 테스트 전략을 사용합니다.

<details>

### 8.1 단위 테스트
- 핵심 유스케이스/도메인 로직에 대해 단위 테스트 작성
  - 예: `ReservationService`의 “이미 예매된 좌석이면 실패” 로직
  - 예: 대기열 순번 계산 로직
- 일부 로직은 **TDD 스타일(테스트 먼저)** 로 연습 예정

### 8.2 통합 테스트
- `@SpringBootTest` 로 JPA + DB 통합 테스트
- 실제 DB에 예약/결제/예매 확정 시나리오를 넣어보고 검증
- Redis, Kafka가 개입되는 경로도 최소 1개 이상 end-to-end 시나리오 테스트

### 8.3 부하 테스트 (k6)
- `/queue/enter`에 대한 대량 트래픽 시나리오
- 여러 유저가 동일 좌석을 동시에 노리는 시나리오
- 응답 시간(p95/p99), 에러율, 처리량 확인

> 목표는 **“테스트 코드 100%”가 아니라,
> **중요한 비즈니스 규칙과 동시성 이슈를 테스트로 재현/보호하는 것**입니다.
</details>

<br><br>

## 9. 📈 Monitoring & Observability

<details>

### 9.1 Spring Actuator + Prometheus
- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `/actuator/prometheus` 로 메트릭 노출
- HTTP 요청 수/지연시간, JVM 메모리/GC, DB 커넥션 풀 등 수집

### 9.2 Grafana
- Prometheus를 데이터 소스로 사용
- 대시보드 구성 예:
  - 요청 수 / 에러율
  - 응답 시간(p95/p99)
  - CPU/메모리 추세
  - (선택) Redis, Kafka Exporter 붙여서 모니터링
</details>

<br><br>

## 🖥 Local Development & Deployment

<details>

### 10.1 로컬 실행 (예시)  

```bash
# 1. 빌드
./gradlew clean build

# 2. docker-compose로 인프라 + 앱 실행
docker-compose up -d

# 3. 접속
# API:      http://localhost:8080
# Grafana:  http://localhost:3000  # 기본 admin/admin
```

### 10.2 개인 서버 배포
- 서버(Ubuntu 등)에 Docker, docker-compose 설치
- 프로젝트 clone 후 동일한 docker-compose.yml로 실행
- 필요 시 Nginx 리버스 프록시 + Let’s Encrypt로 HTTPS 구성

```bash
git pull origin main
./gradlew clean build
docker-compose up -d
```

</details>

<br>
