# 🌮 Traffic Tacos Reservation API

> **30k RPS 대규모 트래픽을 위한 고성능 예약 시스템**  
> Kotlin + Spring Boot WebFlux 기반의 Cloud-Native 리액티브 마이크로서비스

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.25-blue.svg)](https://kotlinlang.org/)
[![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-orange.svg)](https://docs.spring.io/spring-framework/reference/web/webflux.html)
[![gRPC](https://img.shields.io/badge/gRPC-Protocol-lightgrey.svg)](https://grpc.io/)
[![AWS](https://img.shields.io/badge/AWS-Cloud%20Native-yellow.svg)](https://aws.amazon.com/)

---

## 📖 목차

- [프로젝트 비전](#-프로젝트-비전)
- [핵심 기술 선택과 설계 철학](#-핵심-기술-선택과-설계-철학)
- [아키텍처 깊이 들여다보기](#-아키텍처-깊이-들여다보기)
- [구현된 기능](#-구현된-기능)
- [빠른 시작](#-빠른-시작)
- [API 문서](#-api-문서)
- [설정 가이드](#-설정-가이드)
- [테스트 전략](#-테스트-전략)
- [관측성과 모니터링](#-관측성과-모니터링)
- [프로덕션 운영 가이드](#-프로덕션-운영-가이드)
- [성능 최적화 기법](#-성능-최적화-기법)
- [트러블슈팅](#-트러블슈팅)

---

## 🎯 프로젝트 비전

### 왜 이 프로젝트가 특별한가?

Traffic Tacos Reservation API는 단순한 예약 시스템을 넘어, **대규모 트래픽 환경에서의 실전 설계 고민**을 담은 레퍼런스 아키텍처입니다.

```
"30,000 RPS 트래픽 폭주 시나리오에서 
 어떻게 안정적이고 확장 가능한 시스템을 만들 것인가?"
```

이 질문에 대한 답을 찾는 여정에서 다음 핵심 과제를 해결했습니다:

#### 🎪 실전 비즈니스 문제
- **60초 홀드 메커니즘**: 사용자가 좌석을 선택하고 결제하기까지의 유한한 시간
- **오버셀 제로 보장**: 동시성 제어와 분산 트랜잭션
- **멱등성 보장**: 네트워크 재시도로 인한 중복 예약 방지
- **자동 만료 처리**: 60초 후 정확한 리소스 회수

#### 🏗️ 기술적 도전
- **30k RPS 처리**: 완전 논블로킹 리액티브 아키텍처
- **저지연 통신**: gRPC를 통한 마이크로서비스 간 P95 < 250ms
- **이벤트 기반 아키텍처**: 느슨한 결합과 확장성
- **관측 가능성**: 분산 추적과 실시간 메트릭

---

## 💡 핵심 기술 선택과 설계 철학

### 1. 왜 Kotlin + Spring Boot WebFlux인가?

#### Kotlin의 선택 이유
```kotlin
// ✅ Coroutine으로 간결한 비동기 코드
suspend fun createReservation(request: CreateReservationRequest): Response {
    val availability = inventoryService.checkAvailability(request)  // 논블로킹
    val reservation = reservationRepository.save(...)                // 논블로킹
    return Response(reservation)
}

// ❌ Java의 경우 (Reactor)
public Mono<Response> createReservation(CreateReservationRequest request) {
    return inventoryService.checkAvailability(request)
        .flatMap(availability -> reservationRepository.save(...))
        .map(reservation -> new Response(reservation));
}
```

**Kotlin의 장점**:
- **간결성**: Null 안전성, Data class, Smart cast
- **Coroutine**: 동기 스타일로 비동기 코드 작성 (가독성 ↑)
- **DSL 지원**: Gradle Kotlin DSL, 타입 안전한 빌드 스크립트
- **Java 상호운용성**: 기존 Spring 생태계 100% 활용

#### WebFlux의 선택 이유
```
전통적인 Servlet Stack (Thread-per-Request)
┌─────────────────────────────────────┐
│  요청 1 → Thread 1 (Blocking I/O)   │  
│  요청 2 → Thread 2 (Blocking I/O)   │  <- 스레드 수 = 동시 처리량
│  요청 3 → Thread 3 (Blocking I/O)   │     (예: 200 threads = 200 RPS)
└─────────────────────────────────────┘

Reactive Stack (Event Loop)
┌─────────────────────────────────────┐
│  Event Loop (4~8 threads)           │
│  ├─ 요청 1 (Non-blocking I/O)       │  <- 스레드 수 << 동시 처리량
│  ├─ 요청 2 (Non-blocking I/O)       │     (예: 8 threads = 30k RPS)
│  └─ 요청 3 (Non-blocking I/O)       │
└─────────────────────────────────────┘
```

**WebFlux의 장점**:
- **고처리량**: 적은 스레드로 많은 요청 처리 (30k RPS 목표)
- **낮은 리소스 사용**: 메모리와 컨텍스트 스위칭 최소화
- **Backpressure**: 과부하 상황에서 우아한 처리

### 2. 왜 gRPC인가?

#### REST vs gRPC 비교
```
REST (HTTP/1.1 + JSON)
┌──────────────────────────────────────┐
│ POST /v1/inventory/check             │
│ Content-Type: application/json       │
│                                      │
│ {"eventId": "evt_123",               │  <- 텍스트 기반, 파싱 오버헤드
│  "quantity": 2,                      │     평균 ~150ms
│  "seatIds": ["A-1", "A-2"]}          │
└──────────────────────────────────────┘

gRPC (HTTP/2 + Protobuf)
┌──────────────────────────────────────┐
│ CheckAvailabilityRequest {           │
│   event_id: "evt_123"                │  <- 바이너리, 타입 안전
│   quantity: 2                        │     평균 ~40ms
│   seat_ids: ["A-1", "A-2"]           │
│ }                                    │
└──────────────────────────────────────┘
```

**gRPC의 장점**:
- **성능**: Protobuf는 JSON 대비 3~5배 빠른 직렬화
- **타입 안전**: 컴파일 타임에 계약 검증 (proto-contracts)
- **HTTP/2**: Multiplexing, Server Push, Header Compression
- **스트리밍**: Bi-directional streaming 지원 (향후 실시간 기능)

**우리의 선택**:
```kotlin
// inventory-api (포트 8021)와의 gRPC 통신
@Service
class InventoryGrpcService(
    @GrpcClient("inventory-service") 
    private val inventoryStub: InventoryServiceGrpcKt.InventoryServiceCoroutineStub
) {
    suspend fun checkAvailability(request: CheckAvailabilityRequest): CheckAvailabilityResponse {
        return withTimeout(250) {  // 타임아웃 250ms
            inventoryStub.checkAvailability(request)
        }
    }
}
```

### 3. 왜 DynamoDB인가?

#### 관계형 DB vs NoSQL 트레이드오프

**RDS (관계형 DB)의 한계**:
```sql
-- 30k RPS에서 동시성 제어
BEGIN TRANSACTION;
SELECT * FROM reservations WHERE reservation_id = ? FOR UPDATE;  -- Row Lock
UPDATE reservations SET status = 'CONFIRMED' ...;
COMMIT;

-- 문제점:
-- 1. Lock contention (대기 시간 증가)
-- 2. Connection pool 고갈 (수백~수천 커넥션 필요)
-- 3. 수직 확장의 한계 (Scale-up만 가능)
```

**DynamoDB의 장점**:
```kotlin
// Partition Key 기반 O(1) 조회, 자동 샤딩
val reservation = dynamoDbTable.getItem(
    Key.builder()
        .partitionValue(reservationId)  // 분산 저장
        .sortValue(eventId)
        .build()
)

// Optimistic Locking (조건부 업데이트)
dynamoDbTable.updateItem(
    UpdateItemEnhancedRequest.builder(Reservation::class.java)
        .item(reservation)
        .ignoreNulls(true)
        .conditionExpression(
            Expression.builder()
                .expression("attribute_not_exists(reservationId) OR version = :v")
                .putExpressionValue(":v", AttributeValue.builder().n("1").build())
                .build()
        )
        .build()
)
```

**DynamoDB 선택 이유**:
- **무제한 수평 확장**: 파티션 자동 샤딩
- **일관된 레이턴시**: 단일 자릿수 밀리초 (single-digit ms)
- **완전 관리형**: 프로비저닝, 백업, 복구 자동화
- **이벤트 스트리밍**: DynamoDB Streams로 변경 캡처

### 4. 왜 이벤트 기반 아키텍처인가?

#### 동기 vs 비동기 아키텍처

**동기 호출의 문제 (Tight Coupling)**:
```
reservation-api → inventory-api → payment-api
                                       ↓ (장애 발생)
                                       X
                ↓ 
           전체 실패 (Cascading Failure)
```

**이벤트 기반 (Loose Coupling)**:
```
reservation-api → EventBridge → SQS → reservation-worker
                      ↓
                  (비동기 처리)
                      ↓
             inventory-api (독립적 처리)
             
장점:
1. 장애 격리 (Circuit Breaker)
2. 재시도 메커니즘 (DLQ)
3. 확장성 (워커 독립 스케일링)
```

**Outbox Pattern 구현**:
```kotlin
@Transactional
suspend fun createReservation(...) {
    // 1. 비즈니스 로직 실행
    val reservation = reservationRepository.save(...)
    
    // 2. 이벤트를 DB에 저장 (같은 트랜잭션)
    val outboxEvent = OutboxEvent(
        eventId = UUID.randomUUID().toString(),
        eventType = "reservation.created",
        payload = objectMapper.writeValueAsString(reservation),
        status = OutboxStatus.PENDING
    )
    outboxRepository.save(outboxEvent)
    
    // 3. 별도 발행기가 폴링해서 EventBridge로 발행
    // (at-least-once delivery 보장)
}
```

### 5. 멱등성(Idempotency) 설계

#### 문제 상황
```
클라이언트 → [네트워크 타임아웃] → API
               ↓ (재시도)
클라이언트 → [성공 응답] → API

결과: 중복 예약 발생 ❌
```

#### 해결책: Idempotency Key
```kotlin
@Service
class IdempotencyService(
    private val idempotencyRepository: IdempotencyRepository
) {
    suspend fun <T, R> executeIdempotent(
        idempotencyKey: String,
        request: T,
        operation: suspend () -> R
    ): R {
        // 1. 요청 해시 생성 (SHA-256)
        val requestHash = createHash(objectMapper.writeValueAsString(request))
        
        // 2. 기존 요청 확인
        val existing = idempotencyRepository.findByKey(idempotencyKey)
        if (existing != null) {
            if (existing.requestHash == requestHash) {
                // 동일 요청 → 캐시된 응답 반환
                return objectMapper.readValue(existing.responseSnapshot)
            } else {
                // 다른 요청 → 충돌 에러
                throw IdempotencyConflictException()
            }
        }
        
        // 3. 새 요청 실행 후 저장 (TTL: 5분)
        val result = operation()
        idempotencyRepository.saveWithTtl(idempotencyKey, requestHash, result)
        return result
    }
}
```

**실전 적용**:
```http
POST /v1/reservations
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "eventId": "evt_2025_1001",
  "quantity": 2,
  "seatIds": ["A-12", "A-13"]
}

# 동일 요청 재시도 → 201 Created (기존 응답 반환)
# 다른 요청 (같은 Key) → 409 Conflict
```

---

## 🏗️ 아키텍처 깊이 들여다보기

### Traffic Tacos MSA 전체 구조

```
                         ┌─────────────────────┐
                         │   Frontend (React)  │
                         │   Port: 3000        │
                         └──────────┬──────────┘
                                    │ HTTPS/REST
                                    ▼
                         ┌─────────────────────┐
                         │    API Gateway      │  Rate Limiting
                         │  REST: 8000         │  Authentication
                         │  gRPC: 8001         │  Request Routing
                         └──────────┬──────────┘
                                    │ gRPC (Proto-Contracts)
                ┌───────────────────┼───────────────────┐
                │                   │                   │
                ▼                   ▼                   ▼
    ┌────────────────────┐ ┌────────────────────┐ ┌────────────────────┐
    │  Reservation API   │ │   Inventory API    │ │  Payment Sim API   │
    │  REST: 8010        │ │   REST: 8020       │ │   REST: 8030       │
    │  gRPC: 8011        │ │   gRPC: 8021       │ │   gRPC: 8031       │
    │  (THIS SERVICE)    │ │                    │ │                    │
    └─────────┬──────────┘ └─────────┬──────────┘ └─────────┬──────────┘
              │                      │                       │
              │ gRPC                 │ DynamoDB              │ Webhook
              └──────────────────────┤                       │
                                     │                       │
                          ┌──────────▼─────────────┐         │
                          │    DynamoDB Tables     │         │
                          │  - reservations        │         │
                          │  - orders              │         │
                          │  - idempotency (TTL)   │         │
                          │  - outbox              │         │
                          │  - inventory           │         │
                          └────────────────────────┘         │
                                                              │
              ┌───────────────────────────────────────────────┘
              │ EventBridge Events
              ▼
    ┌────────────────────────────────────┐
    │     AWS EventBridge                │
    │  - reservation.created             │
    │  - reservation.confirmed           │
    │  - reservation.expired             │
    │  - payment.approved/failed         │
    └─────────────┬──────────────────────┘
                  │
                  ├─► EventBridge Scheduler (60s 만료 타이머)
                  │
                  ├─► SQS Queue (비동기 처리)
                  │
                  ▼
    ┌────────────────────────────────────┐
    │   Reservation Worker (K8s Job)     │
    │   Port: 8040                       │
    │   - Auto-scaling (KEDA)            │
    │   - Event processing               │
    │   - Expiry handling                │
    └────────────────────────────────────┘
```

### Reservation API 내부 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                  Reservation API (Port 8010)                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │    REST     │  │    gRPC     │  │  Actuator   │        │
│  │ Controller  │  │   Service   │  │   /health   │        │
│  │   :8010     │  │   :8011     │  │  /metrics   │        │
│  └──────┬──────┘  └──────┬──────┘  └─────────────┘        │
│         │                │                                  │
│         │  ┌─────────────▼─────────────┐                   │
│         │  │  Spring Security          │                   │
│         │  │  - JWT Validation         │                   │
│         │  │  - OAuth2 Resource Server │                   │
│         │  └─────────────┬─────────────┘                   │
│         │                │                                  │
│         │  ┌─────────────▼─────────────┐                   │
│         └─►│  Service Layer            │                   │
│            │  ┌─────────────────────┐  │                   │
│            │  │ ReservationService  │  │                   │
│            │  │ - createReservation │  │                   │
│            │  │ - confirmReservation│  │                   │
│            │  │ - cancelReservation │  │                   │
│            │  └──────────┬──────────┘  │                   │
│            │             │              │                   │
│            │  ┌──────────▼──────────┐  │                   │
│            │  │ IdempotencyService  │  │ (SHA-256 Hash)    │
│            │  └──────────┬──────────┘  │                   │
│            │             │              │                   │
│            │  ┌──────────▼──────────┐  │                   │
│            │  │ OutboxPublisher     │  │ (Event Sourcing)  │
│            │  └──────────┬──────────┘  │                   │
│            │             │              │                   │
│            │  ┌──────────▼──────────┐  │                   │
│            │  │ ExpiryService       │  │ (Scheduler)       │
│            │  └─────────────────────┘  │                   │
│            └───────────────────────────┘                   │
│                         │                                   │
│         ┌───────────────┼───────────────┐                  │
│         │               │               │                  │
│    ┌────▼────┐   ┌──────▼──────┐  ┌────▼────┐            │
│    │ DynamoDB│   │InventoryGrpc│  │EventBridge│          │
│    │Repository│  │   Client    │  │ Scheduler │          │
│    └─────────┘   └─────────────┘  └───────────┘            │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                     Observability                           │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐       │
│  │OpenTelemetry │ │  Prometheus  │ │   Micrometer │       │
│  │   Tracing    │ │   Metrics    │ │   Registry   │       │
│  └──────────────┘ └──────────────┘ └──────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

### 핵심 비즈니스 플로우

#### 1. 예약 생성 플로우 (Create Reservation)

```
사용자 요청
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 1. API Gateway → Reservation API                        │
│    POST /v1/reservations                                │
│    Headers: Authorization: Bearer <JWT>                 │
│             Idempotency-Key: <UUID>                     │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 2. Security Layer                                       │
│    - JWT 토큰 검증 (OAuth2 Resource Server)             │
│    - 사용자 권한 확인                                    │
│    - Rate Limiting 체크                                 │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 3. Idempotency Check                                    │
│    - 요청 해시 생성 (SHA-256)                           │
│    - 기존 요청 확인 (DynamoDB idempotency 테이블)        │
│    - 중복 요청 → 캐시 응답 반환 (201)                   │
│    - 신규 요청 → 다음 단계 진행                         │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 4. Inventory Check (gRPC Call)                         │
│    CheckAvailabilityRequest {                           │
│      event_id: "evt_2025_1001"                         │
│      quantity: 2                                        │
│      seat_ids: ["A-12", "A-13"]                        │
│    }                                                    │
│    ↓                                                    │
│    inventory-api:8021 (Timeout: 250ms)                 │
│    ↓                                                    │
│    응답: available = true, available_seats = [...]      │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 5. Hold Seats (gRPC Call)                              │
│    ReserveSeatRequest {                                 │
│      event_id: "evt_2025_1001"                         │
│      seat_ids: ["A-12", "A-13"]                        │
│      reservation_id: "rsv_abc123"                      │
│      user_id: "user_xyz"                               │
│    }                                                    │
│    ↓                                                    │
│    inventory-api가 좌석 HOLD 상태로 변경                │
│    (Conditional Update로 동시성 제어)                   │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 6. Save Reservation (DynamoDB)                         │
│    Reservation {                                        │
│      reservationId: "rsv_abc123"        (PK)           │
│      eventId: "evt_2025_1001"           (SK)           │
│      userId: "user_xyz"                                 │
│      status: HOLD                                       │
│      holdExpiresAt: now() + 60s                        │
│      seatIds: ["A-12", "A-13"]                         │
│      idempotencyKey: "550e8400-..."                    │
│    }                                                    │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 7. Schedule Expiry (EventBridge Scheduler)             │
│    CreateScheduleRequest {                              │
│      name: "reservation-expiry-rsv_abc123"             │
│      scheduleExpression: "at(2025-01-01T12:01:00)"     │
│      target: {                                          │
│        arn: "arn:aws:events:::event-bus/..."          │
│        input: {                                         │
│          type: "reservation.expired"                   │
│          reservationId: "rsv_abc123"                   │
│        }                                                │
│      }                                                  │
│    }                                                    │
│    ↓                                                    │
│    60초 후 자동 만료 이벤트 발행                         │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 8. Publish Event (Outbox Pattern)                      │
│    OutboxEvent {                                        │
│      eventId: "evt_uuid"                               │
│      eventType: "reservation.created"                  │
│      payload: { ...reservation }                       │
│      status: PENDING                                    │
│      createdAt: now()                                   │
│    }                                                    │
│    ↓                                                    │
│    별도 Outbox Poller가 EventBridge로 발행              │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 9. Response                                             │
│    HTTP 201 Created                                     │
│    {                                                    │
│      "reservationId": "rsv_abc123",                    │
│      "status": "HOLD",                                  │
│      "holdExpiresAt": "2025-01-01T12:01:00Z",          │
│      "message": "Reservation created successfully"      │
│    }                                                    │
└─────────────────────────────────────────────────────────┘
```

#### 2. 예약 확정 플로우 (Confirm Reservation)

```
결제 완료 후 → Confirm 요청
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 1. Validate Reservation                                 │
│    - reservationId 존재 확인                            │
│    - status가 HOLD인지 확인                             │
│    - holdExpiresAt > now() 확인 (만료되지 않았는지)     │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 2. Commit Reservation (gRPC Call)                       │
│    CommitReservationRequest {                           │
│      reservation_id: "rsv_abc123"                      │
│      event_id: "evt_2025_1001"                         │
│      seat_ids: ["A-12", "A-13"]                        │
│      payment_intent_id: "pay_xyz789"                   │
│    }                                                    │
│    ↓                                                    │
│    inventory-api가 좌석을 SOLD 상태로 변경              │
│    (최종 확정, 되돌릴 수 없음)                          │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 3. Update Reservation Status                            │
│    reservation.status = CONFIRMED                       │
│    reservation.updatedAt = now()                        │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 4. Create Order Record                                  │
│    Order {                                              │
│      orderId: "ord_xyz789"              (PK)           │
│      reservationId: "rsv_abc123"                       │
│      eventId: "evt_2025_1001"                          │
│      amount: 20000 (100 per seat)                      │
│      status: CONFIRMED                                  │
│      paymentIntentId: "pay_xyz789"                     │
│    }                                                    │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 5. Cancel Scheduled Expiry                              │
│    DeleteScheduleRequest {                              │
│      name: "reservation-expiry-rsv_abc123"             │
│    }                                                    │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 6. Publish Confirmation Event                           │
│    eventType: "reservation.confirmed"                  │
└─────────────────────────────────────────────────────────┘
```

#### 3. 자동 만료 플로우 (Automatic Expiry)

```
60초 경과
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ EventBridge Scheduler 트리거                            │
│    - 정확히 holdExpiresAt 시각에 실행                   │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ EventBridge → SQS → Reservation Worker                  │
│    event: {                                             │
│      type: "reservation.expired",                      │
│      reservationId: "rsv_abc123"                       │
│    }                                                    │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ Worker: Release Hold (gRPC Call)                        │
│    ReleaseHoldRequest {                                 │
│      reservation_id: "rsv_abc123"                      │
│      event_id: "evt_2025_1001"                         │
│      seat_ids: ["A-12", "A-13"]                        │
│      reason: "EXPIRED"                                  │
│    }                                                    │
│    ↓                                                    │
│    inventory-api가 좌석을 AVAILABLE로 복구              │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ Worker: Update Reservation Status                       │
│    reservation.status = EXPIRED                         │
│    reservation.updatedAt = now()                        │
└─────────────────────────────────────────────────────────┘
```

---

## ✅ 구현된 기능

### Core Features (완료 ✅)

| 기능 | 상태 | 설명 |
|-----|------|------|
| **예약 생성** | ✅ | 좌석 가용성 확인 → HOLD 상태 저장 → 60초 타이머 등록 |
| **예약 조회** | ✅ | 예약 ID로 상세 정보 조회 (상태, 만료 시각 등) |
| **예약 확정** | ✅ | 결제 완료 후 좌석 최종 확정 → 주문 생성 |
| **예약 취소** | ✅ | 사용자 요청 또는 자동 만료로 좌석 복구 |
| **60초 자동 만료** | ✅ | EventBridge Scheduler 기반 정확한 타이밍 |
| **멱등성 보장** | ✅ | SHA-256 해시 기반 중복 요청 처리 (TTL 5분) |
| **gRPC 통신** | ✅ | inventory-api와 고성능 통신 (P95 < 250ms) |
| **이벤트 발행** | ✅ | Outbox Pattern으로 신뢰성 있는 이벤트 전파 |

### Infrastructure (완료 ✅)

| 기능 | 상태 | 설명 |
|-----|------|------|
| **DynamoDB 통합** | ✅ | Enhanced Client, Conditional Update |
| **EventBridge** | ✅ | 커스텀 이벤트 버스, Scheduler |
| **JWT 인증** | ✅ | OAuth2 Resource Server, Spring Security |
| **OpenTelemetry** | ✅ | 분산 추적, OTLP Exporter |
| **Prometheus** | ✅ | 비즈니스 메트릭, JVM 메트릭 |
| **Docker** | ✅ | Multi-stage 빌드, 프로덕션 최적화 |
| **Local Dev Env** | ✅ | docker-compose (DynamoDB Local, LocalStack, Grafana) |

### API & Documentation (완료 ✅)

| 기능 | 상태 | 설명 |
|-----|------|------|
| **REST API** | ✅ | RESTful 엔드포인트 (포트 8010) |
| **gRPC Server** | ✅ | ReservationService 구현 (포트 8011) |
| **OpenAPI Spec** | ✅ | Swagger UI, OpenAPI 3.0 |
| **Proto Contracts** | ✅ | 중앙화된 proto-contracts 저장소 |
| **Health Check** | ✅ | `/health`, `/actuator/health` |
| **Metrics Endpoint** | ✅ | `/actuator/prometheus` |

---

## 🚀 빠른 시작

### Prerequisites

```bash
# 필수 도구 설치 확인
java -version       # Java 17+
docker --version    # Docker 20+
docker-compose --version

# AWS CLI 설정 (tacos 프로필)
aws configure --profile tacos
```

### 1. 로컬 개발 환경 시작 (원클릭)

```bash
# 전체 인프라 + 빌드 + 실행
./run_local.sh start

# 실행되는 서비스:
# - DynamoDB Local (포트 8000)
# - LocalStack (포트 4566) - EventBridge, Scheduler 시뮬레이션
# - Prometheus (포트 9090)
# - Grafana (포트 3000)
# - OTEL Collector (포트 4317/4318)
# - Reservation API (포트 8010/8011)
```

### 2. 개별 명령어 사용

```bash
# 1) 인프라만 시작
./run_local.sh setup

# 2) DynamoDB 테이블 생성
./run_local.sh tables

# 3) 애플리케이션 빌드
./run_local.sh build

# 4) 애플리케이션 실행
./run_local.sh run

# 5) 전체 중지
./run_local.sh stop

# 6) 로그 확인
./run_local.sh logs
```

### 3. Proto 파일 생성

```bash
# Traffic Tacos proto-contracts 기반 코드 생성
./generate_proto.sh

# 생성되는 파일:
# - build/generated/source/proto/main/java/
# - build/generated/source/proto/main/grpc/
# - build/generated/source/proto/main/grpckt/
```

### 4. 서비스 확인

```bash
# Health Check
curl http://localhost:8010/health
# {"status": "UP"}

# Swagger UI
open http://localhost:8010/swagger-ui.html

# Prometheus Metrics
curl http://localhost:8010/actuator/prometheus

# Grafana Dashboard
open http://localhost:3000  # admin/admin
```

---

## 📚 API 문서

### REST API Endpoints

#### 1. 예약 생성
```http
POST /v1/reservations
Authorization: Bearer <JWT>
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "eventId": "evt_2025_1001",
  "quantity": 2,
  "seatIds": ["A-12", "A-13"],
  "reservationToken": "rtkn_xyz789"
}
```

**응답 (201 Created)**:
```json
{
  "reservationId": "rsv_abc123",
  "status": "HOLD",
  "holdExpiresAt": "2025-01-01T12:01:00Z",
  "message": "Reservation created successfully"
}
```

#### 2. 예약 조회
```http
GET /v1/reservations/{reservationId}
Authorization: Bearer <JWT>
```

**응답 (200 OK)**:
```json
{
  "reservationId": "rsv_abc123",
  "eventId": "evt_2025_1001",
  "quantity": 2,
  "seatIds": ["A-12", "A-13"],
  "status": "HOLD",
  "holdExpiresAt": "2025-01-01T12:01:00Z",
  "createdAt": "2025-01-01T12:00:00Z",
  "updatedAt": "2025-01-01T12:00:00Z"
}
```

#### 3. 예약 확정
```http
POST /v1/reservations/confirm
Authorization: Bearer <JWT>
Idempotency-Key: 660e8400-e29b-41d4-a716-446655440001 (선택)
Content-Type: application/json

{
  "reservationId": "rsv_abc123",
  "paymentIntentId": "pay_xyz789"
}
```

**응답 (200 OK)**:
```json
{
  "orderId": "ord_xyz789",
  "reservationId": "rsv_abc123",
  "status": "CONFIRMED",
  "message": "Reservation confirmed successfully"
}
```

#### 4. 예약 취소
```http
POST /v1/reservations/cancel
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "reservationId": "rsv_abc123"
}
```

**응답 (200 OK)**:
```json
{
  "reservationId": "rsv_abc123",
  "status": "CANCELLED",
  "message": "Reservation cancelled successfully"
}
```

### gRPC API (포트 8011)

```protobuf
// ReservationService 정의
service ReservationService {
  rpc CreateReservation(CreateReservationRequest) returns (CreateReservationResponse);
  rpc GetReservation(GetReservationRequest) returns (GetReservationResponse);
  rpc ConfirmReservation(ConfirmReservationRequest) returns (ConfirmReservationResponse);
  rpc CancelReservation(CancelReservationRequest) returns (CancelReservationResponse);
}
```

**gRPC 테스트 (grpcurl)**:
```bash
# 서비스 목록 조회
grpcurl -plaintext localhost:8011 list

# 메서드 호출
grpcurl -plaintext -d '{
  "event_id": "evt_2025_1001",
  "quantity": 2,
  "seat_ids": ["A-12", "A-13"]
}' localhost:8011 com.traffic_tacos.reservation.v1.ReservationService/CreateReservation
```

### 에러 응답 포맷

```json
{
  "error": {
    "code": "SEAT_UNAVAILABLE",
    "message": "Requested seats are not available",
    "timestamp": "2025-01-01T12:00:00Z",
    "path": "/v1/reservations",
    "traceId": "abc123def456"
  }
}
```

**에러 코드**:
- `SEAT_UNAVAILABLE`: 좌석 이용 불가
- `RESERVATION_NOT_FOUND`: 예약 없음
- `RESERVATION_EXPIRED`: 예약 만료
- `RESERVATION_ALREADY_CONFIRMED`: 이미 확정됨
- `IDEMPOTENCY_CONFLICT`: 멱등성 키 충돌
- `INVENTORY_SERVICE_ERROR`: 재고 서비스 에러

---

## ⚙️ 설정 가이드

### Environment Variables

```bash
# AWS 설정
AWS_REGION=ap-northeast-2
AWS_PROFILE=tacos
AWS_DYNAMODB_ENDPOINT=http://localhost:8000  # 로컬 개발시

# 애플리케이션 설정
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=8010
GRPC_SERVER_PORT=8011

# gRPC 클라이언트 (Inventory Service)
GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS=static://localhost:8021

# JWT 인증 (프로덕션)
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://auth.traffictacos.com

# Observability
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0

# Resilience4j
RESILIENCE4J_CIRCUITBREAKER_INSTANCES_INVENTORY_GRPC_FAILURE_RATE_THRESHOLD=50
RESILIENCE4J_TIMELIMITER_INSTANCES_INVENTORY_GRPC_TIMEOUT_DURATION=250ms
```

### application.properties

```properties
# Traffic Tacos MSA Port Allocation
server.port=8010
grpc.server.port=8011
grpc.client.inventory-service.address=localhost:8021

# DynamoDB Configuration (프로덕션)
aws.dynamodb.table.reservations=traffic-tacos-reservations
aws.dynamodb.table.orders=traffic-tacos-orders
aws.dynamodb.table.idempotency=traffic-tacos-idempotency
aws.dynamodb.table.outbox=traffic-tacos-outbox

# EventBridge Configuration
aws.eventbridge.bus-name=traffic-tacos-events
aws.eventbridge.scheduler-group=reservation-expiry

# Security (로컬에서는 비활성화)
spring.security.enabled=true
spring.security.oauth2.resourceserver.jwt.issuer-uri=${JWT_ISSUER_URI}

# Management/Actuator
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
management.metrics.export.prometheus.enabled=true

# Logging
logging.level.com.traffictacos=DEBUG
logging.level.io.grpc=INFO
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n
```

### DynamoDB Table Schema

#### reservations 테이블
```json
{
  "TableName": "traffic-tacos-reservations",
  "KeySchema": [
    { "AttributeName": "pk", "KeyType": "HASH" },   // reservationId
    { "AttributeName": "sk", "KeyType": "RANGE" }   // eventId
  ],
  "AttributeDefinitions": [
    { "AttributeName": "pk", "AttributeType": "S" },
    { "AttributeName": "sk", "AttributeType": "S" }
  ],
  "BillingMode": "PAY_PER_REQUEST"
}
```

#### idempotency 테이블 (TTL 활성화)
```json
{
  "TableName": "traffic-tacos-idempotency",
  "KeySchema": [
    { "AttributeName": "idempotencyKey", "KeyType": "HASH" }
  ],
  "AttributeDefinitions": [
    { "AttributeName": "idempotencyKey", "AttributeType": "S" }
  ],
  "TimeToLiveSpecification": {
    "Enabled": true,
    "AttributeName": "expiresAt"  // 5분 후 자동 삭제
  }
}
```

---

## 🧪 테스트 전략

### Test Pyramid

```
           ┌────────────┐
          ╱              ╲
         ╱  E2E Tests     ╲    10% - Testcontainers + gRPC stub
        ╱──────────────────╲
       ╱                    ╲
      ╱  Integration Tests   ╲  30% - DynamoDB Local + MockWebServer
     ╱────────────────────────╲
    ╱                          ╲
   ╱        Unit Tests          ╲ 60% - MockK + Kotest
  ╱──────────────────────────────╲
```

### 1. Unit Tests

```bash
# 전체 유닛 테스트 실행
./gradlew test

# 특정 테스트 클래스 실행
./gradlew test --tests ReservationServiceTest

# 커버리지 리포트 생성
./gradlew jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

**예제 테스트**:
```kotlin
@Test
fun `should create reservation with valid request`() = runTest {
    // Given
    val request = CreateReservationRequest(
        eventId = "evt_123",
        quantity = 2,
        seatIds = listOf("A-12", "A-13")
    )
    
    coEvery { inventoryGrpcService.checkAvailability(any()) } returns 
        CheckAvailabilityResponse(available = true)
    coEvery { inventoryGrpcService.reserveSeat(any()) } returns 
        ReserveSeatResponse(status = HoldStatus.HOLD_STATUS_ACTIVE)
    
    // When
    val response = reservationService.createReservation(request, "user123", "idem123")
    
    // Then
    assertEquals(ReservationStatus.HOLD, response.status)
    assertNotNull(response.reservationId)
    assertTrue(response.holdExpiresAt.isAfter(Instant.now()))
}
```

### 2. Integration Tests

```bash
# 통합 테스트 실행 (Testcontainers 사용)
./gradlew integrationTest

# Docker가 실행 중이어야 함
docker ps
```

**예제 통합 테스트**:
```kotlin
@SpringBootTest
@Testcontainers
class ReservationControllerIntegrationTest {
    
    @Container
    val dynamoDbContainer = GenericContainer<Nothing>("amazon/dynamodb-local:latest")
        .apply { withExposedPorts(8000) }
    
    @Test
    fun `should handle end-to-end reservation flow`() = runTest {
        // 1. Create reservation
        val createResponse = webTestClient
            .post()
            .uri("/v1/reservations")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .bodyValue(createRequest)
            .exchange()
            .expectStatus().isCreated
            .returnResult<CreateReservationResponse>()
            .responseBody.awaitFirst()
        
        // 2. Get reservation
        val getResponse = webTestClient
            .get()
            .uri("/v1/reservations/${createResponse.reservationId}")
            .exchange()
            .expectStatus().isOk
            .returnResult<ReservationDetailsResponse>()
            .responseBody.awaitFirst()
        
        assertEquals(ReservationStatus.HOLD, getResponse.status)
    }
}
```

### 3. Performance Tests

```bash
# Gatling 성능 테스트
./gradlew gatlingRun

# JMeter 테스트
./gradlew jmeterRun
```

---

## 📊 관측성과 모니터링

### OpenTelemetry Tracing

```kotlin
// 자동 계측 (Spring Boot Auto-configuration)
@Service
class ReservationService {
    @WithSpan  // 수동 span 추가 (필요시)
    suspend fun createReservation(...) {
        // 자동으로 trace ID와 span ID가 로그에 포함됨
        logger.info("Creating reservation")  
        // [traceId=abc123,spanId=def456]
    }
}
```

**Trace 예제**:
```
Trace ID: abc123def456
├─ gateway-api: POST /api/v1/reservations (150ms)
│  ├─ reservation-api: POST /v1/reservations (120ms)
│  │  ├─ idempotency-check (5ms)
│  │  ├─ inventory-grpc: CheckAvailability (40ms)
│  │  ├─ inventory-grpc: ReserveSeat (35ms)
│  │  ├─ dynamodb: PutItem (15ms)
│  │  └─ eventbridge-scheduler: CreateSchedule (10ms)
│  └─ response (10ms)
```

### Prometheus Metrics

**비즈니스 메트릭**:
```kotlin
@Service
class ReservationService(
    private val meterRegistry: MeterRegistry
) {
    private val reservationCounter = meterRegistry.counter(
        "reservation.created.total",
        Tags.of("status", "success")
    )
    
    suspend fun createReservation(...) {
        // ...
        reservationCounter.increment()
    }
}
```

**주요 메트릭**:
```promql
# HTTP 요청 레이턴시
http_server_requests_seconds_bucket{uri="/v1/reservations",method="POST"}

# gRPC 클라이언트 레이턴시
grpc_client_duration_seconds{service="InventoryService",method="CheckAvailability"}

# 예약 상태별 카운트
reservation_status_total{status="HOLD"}
reservation_status_total{status="CONFIRMED"}

# JVM 메트릭
jvm_memory_used_bytes{area="heap"}
jvm_gc_pause_seconds_sum
```

### Grafana Dashboard

**대시보드 구성**:
1. **Overview Panel**: 전체 요청 수, 에러율, P95 레이턴시
2. **Business Metrics**: 예약 생성/확정/취소 추이
3. **gRPC Performance**: 서비스별 호출 레이턴시
4. **JVM Health**: Heap 사용량, GC 시간
5. **Error Analysis**: 에러 코드별 분포

```bash
# Grafana 접속
open http://localhost:3000
# ID: admin, PW: admin

# 프로메테우스 데이터소스 자동 구성됨
# Dashboard → Import → 업로드 (grafana/dashboards/*.json)
```

---

## 🏭 프로덕션 운영 가이드

### 1. 배포 체크리스트

**Pre-deployment**:
- [ ] AWS 리소스 확인 (DynamoDB, EventBridge, IAM Role)
- [ ] Secrets Manager에 민감 정보 저장
- [ ] 로드 테스트 완료 (30k RPS 검증)
- [ ] 롤백 계획 수립

**Deployment**:
- [ ] Blue-Green 배포 또는 Canary 배포
- [ ] Health Check 통과 확인
- [ ] Smoke Test 실행
- [ ] 메트릭 모니터링 (5분간)

**Post-deployment**:
- [ ] 에러율 < 1% 확인
- [ ] P95 레이턴시 < 120ms 확인
- [ ] 알람 정상 동작 확인

### 2. JVM 튜닝

```bash
# 프로덕션 JVM 옵션
java \
  -Xms2g -Xmx2g \                          # Heap 크기 고정 (GC 최적화)
  -XX:+UseG1GC \                           # G1 GC 사용 (저지연)
  -XX:MaxGCPauseMillis=100 \               # GC 일시정지 100ms 이하
  -XX:+UseStringDeduplication \            # 문자열 중복 제거
  -XX:+HeapDumpOnOutOfMemoryError \        # OOM 시 Heap Dump
  -XX:HeapDumpPath=/var/log/heapdump \
  -Dspring.profiles.active=prod \
  -jar reservation-api.jar
```

### 3. Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: reservation-api
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    spec:
      containers:
      - name: reservation-api
        image: reservation-api:1.0.0
        ports:
        - containerPort: 8010
          name: http
        - containerPort: 8011
          name: grpc
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8010
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8010
          initialDelaySeconds: 10
          periodSeconds: 5
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: AWS_REGION
          value: "ap-northeast-2"
---
apiVersion: v1
kind: Service
metadata:
  name: reservation-api
spec:
  type: ClusterIP
  ports:
  - name: http
    port: 8010
    targetPort: 8010
  - name: grpc
    port: 8011
    targetPort: 8011
  selector:
    app: reservation-api
```

### 4. Horizontal Pod Autoscaling

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: reservation-api-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: reservation-api
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  - type: Pods
    pods:
      metric:
        name: http_requests_per_second
      target:
        type: AverageValue
        averageValue: "1000"  # Pod당 1k RPS
```

---

## ⚡ 성능 최적화 기법

### 1. 리액티브 프로그래밍 최적화

```kotlin
// ❌ 블로킹 코드 (피해야 함)
fun createReservation(...): Mono<Response> {
    val availability = inventoryClient.check(...).block()  // 블로킹!
    val reservation = repository.save(...).block()         // 블로킹!
    return Mono.just(Response(reservation))
}

// ✅ 완전 논블로킹
suspend fun createReservation(...): Response {
    val availability = inventoryClient.check(...)  // 코루틴 suspend
    val reservation = repository.save(...)         // 코루틴 suspend
    return Response(reservation)
}
```

### 2. DynamoDB 최적화

```kotlin
// ❌ Sequential 조회 (N+1 문제)
suspend fun getReservationsWithOrders(ids: List<String>): List<Data> {
    return ids.map { id ->
        val reservation = reservationRepo.findById(id)  // N번 호출
        val order = orderRepo.findByReservation(id)     // N번 호출
        Data(reservation, order)
    }
}

// ✅ Batch Get
suspend fun getReservationsWithOrders(ids: List<String>): List<Data> {
    val reservations = reservationRepo.batchGet(ids)  // 1번 호출 (최대 100개)
    val orders = orderRepo.batchGet(...)
    return reservations.zip(orders)
}
```

### 3. gRPC Connection Pooling

```kotlin
@Configuration
class GrpcConfig {
    @Bean
    fun inventoryChannel(): ManagedChannel {
        return ManagedChannelBuilder
            .forAddress("inventory-api", 8021)
            .usePlaintext()
            .maxInboundMessageSize(10 * 1024 * 1024)  // 10MB
            .keepAliveTime(30, TimeUnit.SECONDS)      // Keep-alive
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
```

### 4. Caffeine Cache

```kotlin
@Configuration
class CacheConfig {
    @Bean
    fun reservationCache(): Cache<String, Reservation> {
        return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats()
            .build()
    }
}

@Service
class ReservationService(
    private val cache: Cache<String, Reservation>
) {
    suspend fun getReservation(id: String): Reservation {
        return cache.get(id) {
            reservationRepository.findById(id) ?: throw NotFoundException()
        }
    }
}
```

---

## 🔧 트러블슈팅

### 1. gRPC Connection Timeout

**증상**:
```
io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: deadline exceeded after 250ms
```

**해결**:
```kotlin
// 타임아웃 증가 (신중하게)
withTimeout(500) {  // 250ms → 500ms
    inventoryStub.checkAvailability(request)
}

// Circuit Breaker 설정 확인
resilience4j.circuitbreaker.instances.inventory-grpc.timeout-duration=500ms
```

### 2. DynamoDB Throttling

**증상**:
```
ProvisionedThroughputExceededException: Rate of requests exceeds the allowed throughput
```

**해결**:
```bash
# On-demand 모드로 전환
aws dynamodb update-table \
  --table-name traffic-tacos-reservations \
  --billing-mode PAY_PER_REQUEST \
  --profile tacos

# 또는 Auto Scaling 설정
aws application-autoscaling register-scalable-target \
  --service-namespace dynamodb \
  --resource-id "table/traffic-tacos-reservations" \
  --scalable-dimension "dynamodb:table:ReadCapacityUnits" \
  --min-capacity 5 \
  --max-capacity 100
```

### 3. Memory Leak

**증상**:
```
java.lang.OutOfMemoryError: Java heap space
```

**진단**:
```bash
# Heap Dump 분석
jmap -dump:live,format=b,file=heap.bin <PID>

# Eclipse MAT로 분석
# https://www.eclipse.org/mat/

# 일반적인 원인:
# - WebClient/gRPC Channel이 닫히지 않음
# - 큰 객체를 캐시에 무한정 저장
# - Reactive Stream이 dispose되지 않음
```

**해결**:
```kotlin
// ✅ 리소스 정리
@PreDestroy
fun cleanup() {
    grpcChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    cache.invalidateAll()
}
```

### 4. Reactive Debugging

```kotlin
// Reactor Context에 trace 정보 추가
Hooks.onOperatorDebug()  // 개발 환경에서만 (성능 영향)

// 또는 Checkpoint 사용
suspend fun createReservation(...) {
    inventoryClient.check(...)
        .checkpoint("after-inventory-check")  // 에러 발생 시 위치 표시
    // ...
}
```

---

## 🤝 Contributing

### Branch Strategy

```
main (프로덕션)
  ├─ develop (개발)
  │   ├─ feature/add-cache
  │   ├─ feature/improve-metrics
  │   └─ bugfix/fix-timeout
  └─ hotfix/critical-bug
```

### Commit Convention

```bash
# 커밋 메시지 규칙 (Conventional Commits)
<type>(<scope>): <subject>

# 예:
feat(reservation): add idempotency support
fix(grpc): resolve connection timeout issue
docs(readme): update architecture diagram
perf(dynamodb): optimize batch operations
```

### Pull Request Template

```markdown
## 변경 사항
- [ ] 새로운 기능 추가
- [ ] 버그 수정
- [ ] 성능 개선
- [ ] 문서 업데이트

## 테스트
- [ ] 유닛 테스트 추가/업데이트
- [ ] 통합 테스트 통과
- [ ] 로드 테스트 완료 (필요시)

## 체크리스트
- [ ] 코드 리뷰 완료
- [ ] CI/CD 파이프라인 통과
- [ ] 문서 업데이트
```

---

## 📄 라이선스

This project is licensed under the MIT License.

---

## 🙏 Acknowledgments

이 프로젝트는 다음 기술과 커뮤니티의 영향을 받았습니다:

- **Spring Boot & WebFlux**: 리액티브 프로그래밍의 힘
- **Kotlin Coroutines**: 우아한 비동기 처리
- **gRPC & Protobuf**: 고성능 마이크로서비스 통신
- **AWS Managed Services**: 운영 부담 최소화
- **OpenTelemetry**: 관측 가능성 표준화

---

## 📞 Contact & Support

- **Issues**: [GitHub Issues](https://github.com/traffic-tacos/reservation-api/issues)
- **Docs**: `/docs` 디렉토리의 상세 가이드
- **Monitoring**: Grafana 대시보드에서 실시간 상태 확인

---

**Built with ❤️ by Traffic Tacos Team**

*"대규모 트래픽을 우아하게 처리하는 방법을 배우는 여정"*
