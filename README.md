# 🎟️ High-Concurrency Ticketing Service  
### Redis Queue + Kafka + k6 + Grafana + Docker, 그리고 Java/Spring/JPA 중급 공부용

대규모 동시 접속을 가정한 **콘서트 티켓팅 서비스**입니다.  
단순 CRUD 샘플이 아니라, **실제 티켓팅 도메인 + 고동시성 + 운영/배포까지** 전부 묶어서

> **“중급 백엔드 개발자 이상이 알아야 할 것들을 한 번에 공부하고 검증하기 위한 프로젝트”**

라는 목표로 설계했습니다.

<br><br>

## 1. 🎯 Motivation

이 프로젝트의 목적은 단순히 “예매 기능 만들었다”가 아니라:

- **Java / Spring 중급 수준 문법과 패턴을 직접 써 보는 것**
- **JPA에 맞는 도메인/DB 설계 경험 쌓기**
- **Redis, Kafka를 이용한 대기열/비동기 처리 설계**
- **k6 + Grafana를 이용한 부하 테스트 & 모니터링**
- **Docker로 패키징해서 개인 서버에 직접 배포까지 해보는 것**

즉, **중급 개발자로 성장하기 위해 필요한 기술 스택 전체를 한 도메인 안에 포함한 학습용 실전 프로젝트**입니다.

<br><br>

## 2. 📚 Learning Goals (학습 목표)

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

<br><br>

## 3. 🧱 Tech Stack

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

<br><br>

## 4. 🏗 Architecture

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

<br><br>

## 5. 🎭 Domain & Features

### 5.1 Domain Model (요약)
- `User` : 사용자
- `Concert` : 공연 정보
- `Schedule` : 공연 회차 (공연 + 날짜/시간)
- `Seat` : 회차별 좌석 (좌석 번호, 가격 등)
- `Reservation` : 최종 예매 (누가, 어떤 회차, 어떤 좌석)
- `PaymentOrder` : 결제 주문 상태 관리 (READY / PAID / CANCELLED 등)

**JPA 설계 포인트**
- 다대다 지양, 항상 중간 엔티티 사용
- 연관관계는 단방향 ManyToOne 중심
- 값 타입(예: 금액, 좌석 위치 등)을 @Embeddable로 분리할 계획
