# DynamoDB Schema Mapping 문제 해결 기록

**작성일**: 2025-10-09  
**심각도**: Critical  
**영향 범위**: Reservation API 전체 기능 불가  
**해결 시간**: 약 2시간

---

## 📋 목차

1. [문제 발견](#1-문제-발견)
2. [초기 진단](#2-초기-진단)
3. [근본 원인 분석](#3-근본-원인-분석)
4. [해결 과정](#4-해결-과정)
5. [배운 점](#5-배운-점)
6. [예방 조치](#6-예방-조치)

---

## 1. 문제 발견

### 1.1 초기 증상

#### 증상 1: Queue Enter API 403 Forbidden
```json
{
  "error": {
    "code": "NOT_READY",
    "message": "Your turn has not arrived yet",
    "trace_id": ""
  }
}
```

**원인**: 사용자가 대기열 상태 확인 없이 직접 Enter API 호출  
**해결**: 이는 정상 동작 (API 계약 준수)

#### 증상 2: Reservation API 500 Internal Server Error (핵심 문제)
```json
{
  "error": {
    "code": "RESERVATION_ERROR",
    "message": "Failed to create reservation",
    "trace_id": ""
  }
}
```

**요청 정보**:
```
POST https://api.traffictacos.store/api/v1/reservations
Content-Type: application/json

{
  "event_id": "evt_2025_1001",
  "seat_ids": ["VIP-9-17"],
  "quantity": 1,
  "reservation_token": "9d97a952-b1cb-41c4-91b4-4de0548f3045",
  "user_id": "user_1760001329804"
}
```

### 1.2 사용자 영향
- ❌ 예약 생성 불가능
- ❌ 티켓 구매 플로우 완전 차단
- ❌ 전체 비즈니스 기능 마비

---

## 2. 초기 진단

### 2.1 Gateway API 로그 분석

```json
{
  "level": "debug",
  "msg": "Creating reservation via gRPC",
  "event_id": "evt_2025_1001",
  "user_id": "bccf1ad3-9f03-4b40-9357-9ca3f37b3339",
  "quantity": 1,
  "seat_ids": ["VIP-14-12"],
  "latency_ms": 26
}
```

**발견 사항**:
- ✅ Gateway API는 정상 동작
- ✅ gRPC 호출은 성공적으로 전달됨
- ❌ Reservation API에서 500 에러 응답

### 2.2 Reservation API 로그 분석

```
2025-10-09 10:29:49 [DefaultDispatcher-worker-1] INFO  c.t.r.grpc.ReservationGrpcService - 
gRPC CreateReservation called for eventId: evt_2025_1001, userId: bccf1ad3-9f03-4b40-9357-9ca3f37b3339

2025-10-09 10:29:51 [DefaultDispatcher-worker-1] ERROR c.t.r.grpc.ReservationGrpcService - 
Unexpected error in createReservation

software.amazon.awssdk.services.dynamodb.model.DynamoDbException: 
One or more parameter values were invalid: Missing the key pk in the item 
(Service: DynamoDb, Status Code: 400, Request ID: F088PTM4CGJ0NMF9SRRN3HDI6JVV4KQNSO5AEMVJF66Q9ASUAAJG)
```

**핵심 에러**: `Missing the key pk in the item`

### 2.3 잘못된 가설들

#### 가설 1: gRPC 포트 불일치 ❌
```
Gateway API 설정: reservation-api:9090
실제 Reservation API: reservation-api:8011
```

**검증 결과**: 
- gRPC 호출은 실제로 도달하고 있었음
- 포트 문제가 아니라 내부 처리 실패

#### 가설 2: AWS 인증 문제 (부분 맞음) ⚠️
```kotlin
// 잘못된 구현
.credentialsProvider(ProfileCredentialsProvider.create("tacos"))
```

**검증 결과**:
- 로컬 개발에서는 동작
- Kubernetes 환경에서는 IRSA 사용 필요
- 하지만 이것만이 문제는 아니었음

---

## 3. 근본 원인 분석

### 3.1 DynamoDB 테이블 스키마

실제 AWS DynamoDB 테이블 구조:

```bash
$ aws dynamodb describe-table --table-name ticket-reservation-reservations

KeySchema:
- AttributeName: pk    (HASH)
- AttributeName: sk    (RANGE)
```

**모든 테이블이 복합 키 사용**:
- `ticket-reservation-reservations`: pk/sk
- `ticket-reservation-orders`: pk/sk
- `ticket-reservation-outbox`: pk/sk

### 3.2 Kotlin 엔티티 정의 (잘못됨)

#### 문제가 있던 Reservation 엔티티
```kotlin
@DynamoDbBean
data class Reservation(
    @get:DynamoDbPartitionKey
    var reservationId: String = "",        // ❌ DynamoDB는 "pk" 기대
    
    var eventId: String = "",              // ❌ Sort Key 미지정
    var userId: String = "",
    // ...
)
```

#### 문제가 있던 OutboxEvent 엔티티
```kotlin
@DynamoDbBean
data class OutboxEvent(
    @get:DynamoDbPartitionKey
    var outboxId: String = "",             // ❌ DynamoDB는 "pk" 기대
    
    var eventType: String = "",            // ❌ Sort Key 미지정
    // ...
)
```

#### 문제가 있던 Order 엔티티
```kotlin
@DynamoDbBean
data class Order(
    @get:DynamoDbPartitionKey
    var orderId: String = "",              // ❌ DynamoDB는 "pk" 기대
    
    var reservationId: String = "",        // ❌ Sort Key 미지정
    // ...
)
```

### 3.3 왜 문제가 발생했는가?

**AWS SDK Enhanced DynamoDB의 동작 원리**:

1. **Attribute 이름 매핑 불일치**:
   ```kotlin
   // 엔티티: reservationId
   // 테이블: pk
   // → AWS SDK는 "reservationId"라는 속성을 찾음 (존재하지 않음)
   // → "pk"는 매핑되지 않아서 누락됨
   ```

2. **Sort Key 누락**:
   ```kotlin
   // eventId는 단순 속성으로 인식
   // DynamoDB는 sk가 필수인데 제공되지 않음
   ```

3. **결과**:
   ```
   DynamoDbException: Missing the key pk in the item
   ```

---

## 4. 해결 과정

### 4.1 Phase 1: gRPC 포트 수정 (선행 작업)

**변경 사항**:
```diff
# gateway-api/internal/config/config.go
type ReservationAPIConfig struct {
-    GRPCAddress string `default:"reservation-api.tickets-api.svc.cluster.local:9090"`
+    GRPCAddress string `default:"reservation-api.tickets-api.svc.cluster.local:8011"`
}

type PaymentAPIConfig struct {
-    GRPCAddress string `default:"payment-sim-api.tickets-api.svc.cluster.local:9090"`
+    GRPCAddress string `default:"payment-sim-api.tickets-api.svc.cluster.local:8031"`
}
```

**커밋**: `daea3e0`

**효과**: gRPC 통신 경로는 정상화되었으나 500 에러는 여전히 발생

---

### 4.2 Phase 2: AWS 인증 수정 (IRSA 지원)

**문제점**:
```kotlin
// Kubernetes 환경에서 AWS Profile 사용 불가
.credentialsProvider(ProfileCredentialsProvider.create("tacos"))
```

**해결책**:
```kotlin
// DynamoDbConfig.kt
@Value("\${aws.profile:}") // 기본값을 빈 문자열로 변경
private lateinit var profile: String

fun dynamoDbClient(): DynamoDbClient {
    val builder = DynamoDbClient.builder()
        .region(Region.of(region))
    
    // 조건부 Credentials Provider
    if (profile.isNotEmpty()) {
        // 로컬 개발: AWS CLI Profile 사용
        builder.credentialsProvider(ProfileCredentialsProvider.create(profile))
    } else {
        // Kubernetes: IRSA (IAM Roles for Service Accounts) 사용
        builder.credentialsProvider(DefaultCredentialsProvider.create())
    }
    
    return builder.build()
}
```

**커밋**: `813ff44`, `6833319`

**효과**: AWS 인증 문제 해결, 하지만 여전히 "Missing the key pk" 에러

---

### 4.3 Phase 3: DynamoDB 엔티티 키 매핑 수정 (최종 해결)

#### Step 1: Reservation 엔티티 수정

```kotlin
@DynamoDbBean
data class Reservation(
    @get:DynamoDbPartitionKey
    @get:DynamoDbAttribute("pk")          // ✅ DynamoDB "pk"로 매핑
    var reservationId: String = "",
    
    @get:DynamoDbSortKey                  // ✅ Sort Key 지정
    @get:DynamoDbAttribute("sk")          // ✅ DynamoDB "sk"로 매핑
    var eventId: String = "",
    
    var userId: String = "",
    var quantity: Int = 0,
    var seatIds: List<String> = emptyList(),
    var status: ReservationStatus = ReservationStatus.PENDING,
    var holdExpiresAt: Instant? = null,
    var holdToken: String? = null,
    var idempotencyKey: String = "",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)
```

**커밋**: `f25a942`

**효과**: Reservation 생성은 성공했으나 OutboxEvent 저장 시 같은 에러 발생

#### Step 2: 모든 엔티티 일괄 수정

**OutboxEvent 수정**:
```kotlin
@DynamoDbBean
data class OutboxEvent(
    @get:DynamoDbPartitionKey
    @get:DynamoDbAttribute("pk")
    var outboxId: String = "",
    
    @get:DynamoDbSortKey
    @get:DynamoDbAttribute("sk")
    var eventType: String = "",
    
    var payload: String = "",
    var status: OutboxStatus = OutboxStatus.PENDING,
    var attempts: Int = 0,
    var nextRetryAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)
```

**Order 수정**:
```kotlin
@DynamoDbBean
data class Order(
    @get:DynamoDbPartitionKey
    @get:DynamoDbAttribute("pk")
    var orderId: String = "",
    
    @get:DynamoDbSortKey
    @get:DynamoDbAttribute("sk")
    var reservationId: String = "",
    
    var eventId: String = "",
    var userId: String = "",
    var amount: BigDecimal = BigDecimal.ZERO,
    var status: OrderStatus = OrderStatus.PENDING,
    var paymentIntentId: String = "",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)
```

**커밋**: `997d288`

**효과**: ✅ 완전 해결! 모든 DynamoDB 작업 정상 동작

---

### 4.4 배포 과정

#### CI/CD 파이프라인
```
코드 푸시 (997d288)
  ↓
GitHub Actions 트리거
  ↓
Gradle 빌드 + Docker 이미지 생성
  ↓
ECR 푸시 (sha256:7de5821e...)
  ↓
Kubernetes Pod 재시작
  ↓
새 이미지로 배포 완료 (21:10:22)
```

#### 배포 검증
```bash
# ECR 최신 이미지 확인
$ aws ecr describe-images --repository-name traffic-tacos-reservation-api
latest: sha256:7de5821e... (21:10:22) ✅

# Pod 이미지 확인
$ kubectl describe pod -n tacos-app reservation-api-c44c78cd8-spnnc
Image ID: sha256:7de5821e... ✅

# 기능 테스트
POST /api/v1/reservations
HTTP/1.1 201 Created ✅
```

---

## 5. 배운 점

### 5.1 DynamoDB Enhanced Client 주의사항

**핵심 교훈**:
```kotlin
// ❌ 잘못된 가정: 필드 이름이 자동으로 매핑됨
@get:DynamoDbPartitionKey
var reservationId: String = ""  // DynamoDB는 "reservationId"를 찾음

// ✅ 올바른 방법: 명시적 attribute 매핑
@get:DynamoDbPartitionKey
@get:DynamoDbAttribute("pk")    // DynamoDB "pk"로 매핑
var reservationId: String = ""
```

**권장 사항**:
1. 테이블 스키마를 먼저 확인하고 엔티티 설계
2. 복합 키 테이블은 반드시 Sort Key 지정
3. `@DynamoDbAttribute`로 명시적 매핑 권장

### 5.2 Kubernetes 환경의 AWS 인증

**로컬 개발 vs 프로덕션**:

| 환경 | 인증 방식 | Credentials Provider |
|-----|---------|---------------------|
| 로컬 개발 | AWS CLI Profile | `ProfileCredentialsProvider.create("tacos")` |
| Kubernetes | IRSA (IAM Roles for Service Accounts) | `DefaultCredentialsProvider.create()` |

**구현 패턴**:
```kotlin
@Value("\${aws.profile:}")
private lateinit var profile: String

fun awsClient(): AWSClient {
    val builder = AWSClient.builder()
    
    if (profile.isNotEmpty()) {
        builder.credentialsProvider(ProfileCredentialsProvider.create(profile))
    } else {
        builder.credentialsProvider(DefaultCredentialsProvider.create())
    }
    
    return builder.build()
}
```

### 5.3 체계적인 문제 해결 프로세스

**성공적인 진단 흐름**:
```
1. 증상 확인 (500 에러)
   ↓
2. 로그 분석 (Gateway → Reservation)
   ↓
3. 에러 메시지 해석 ("Missing the key pk")
   ↓
4. 테이블 스키마 확인 (pk/sk 복합 키)
   ↓
5. 엔티티 코드 검토 (매핑 누락 발견)
   ↓
6. 수정 및 검증
```

**핵심 도구**:
- `kubectl logs`: 실시간 로그 모니터링
- `aws dynamodb describe-table`: 스키마 확인
- `kubectl describe pod`: 이미지 버전 검증

---

## 6. 예방 조치

### 6.1 통합 테스트 강화

**추가 권장 테스트**:

```kotlin
@SpringBootTest
@DynamoDbTest
class ReservationRepositoryTest {
    
    @Test
    fun `should save reservation with composite key`() {
        val reservation = Reservation(
            reservationId = "rsv_test_001",
            eventId = "evt_test_001",
            userId = "user_test_001",
            quantity = 2
        )
        
        // 저장
        reservationRepository.save(reservation)
        
        // 검증: DynamoDB에서 실제로 조회 가능한지
        val saved = reservationRepository.findById(
            ReservationId(pk = "rsv_test_001", sk = "evt_test_001")
        )
        
        assertThat(saved).isNotNull
        assertThat(saved.reservationId).isEqualTo("rsv_test_001")
        assertThat(saved.eventId).isEqualTo("evt_test_001")
    }
    
    @Test
    fun `should map entity fields to DynamoDB pk sk`() {
        // DynamoDB 테이블 스키마 검증
        val tableSchema = dynamoDbTable.tableSchema()
        
        assertThat(tableSchema.keys).containsExactly("pk", "sk")
        assertThat(tableSchema.attributes).contains(
            "pk" to "reservationId",
            "sk" to "eventId"
        )
    }
}
```

### 6.2 문서화 개선

**엔티티 코드에 주석 추가**:

```kotlin
/**
 * Reservation 엔티티
 * 
 * DynamoDB 테이블: ticket-reservation-reservations
 * 
 * Key Schema:
 * - pk (HASH): reservationId 매핑
 * - sk (RANGE): eventId 매핑
 * 
 * 주의: @DynamoDbAttribute로 명시적 매핑 필요
 */
@DynamoDbBean
data class Reservation(
    @get:DynamoDbPartitionKey
    @get:DynamoDbAttribute("pk")
    var reservationId: String = "",
    
    @get:DynamoDbSortKey
    @get:DynamoDbAttribute("sk")
    var eventId: String = "",
    // ...
)
```

### 6.3 개발 환경 설정 가이드

**README.md 추가 내용**:

```markdown
## DynamoDB 설정

### 테이블 키 스키마
모든 테이블은 복합 키를 사용합니다:
- Primary Key: pk (Partition Key)
- Sort Key: sk (Range Key)

### 엔티티 매핑 규칙
1. @DynamoDbAttribute("pk")로 Partition Key 매핑
2. @DynamoDbAttribute("sk")로 Sort Key 매핑
3. Sort Key가 없는 경우 단일 키 테이블 생성 필요

### AWS 인증 설정
- 로컬: `aws.profile=tacos` (application-local.properties)
- Kubernetes: IRSA 자동 인증 (aws.profile 미설정)
```

### 6.4 CI/CD 파이프라인 개선

**테이블 스키마 검증 단계 추가**:

```yaml
# .github/workflows/build.yml
- name: Validate DynamoDB Schema
  run: |
    echo "Checking table schemas..."
    for table in ticket-reservation-reservations ticket-reservation-orders ticket-reservation-outbox
    do
      echo "Checking $table..."
      aws dynamodb describe-table --table-name $table \
        --query "Table.KeySchema" --output json
    done
    
    echo "Running schema validation tests..."
    ./gradlew test --tests "*SchemaValidationTest"
```

---

## 7. 결론

### 최종 해결 내역

| 항목 | Before | After | 효과 |
|-----|--------|-------|------|
| **Reservation 엔티티** | `reservationId` (키 없음) | `pk` ← reservationId, `sk` ← eventId | ✅ 저장 성공 |
| **Order 엔티티** | `orderId` (키 없음) | `pk` ← orderId, `sk` ← reservationId | ✅ 저장 성공 |
| **OutboxEvent 엔티티** | `outboxId` (키 없음) | `pk` ← outboxId, `sk` ← eventType | ✅ 저장 성공 |
| **AWS 인증** | Profile 하드코딩 | 조건부 Provider | ✅ K8s/로컬 모두 지원 |
| **gRPC 포트** | 9090 (잘못됨) | 8011, 8031 (표준화) | ✅ 통신 정상화 |

### 커밋 이력

```bash
997d288 - fix: Map all DynamoDB entities to pk/sk schema (Reservation, Order, OutboxEvent)
f25a942 - fix: Map Reservation entity keys to DynamoDB pk/sk schema
813ff44 - fix: Use DefaultCredentialsProvider for Kubernetes IRSA support
6833319 - fix: Remove AWS profile config for Kubernetes IRSA compatibility
daea3e0 - fix: Update gRPC ports to standard allocation (8011, 8031)
```

### 비즈니스 영향

- ✅ **예약 생성 기능 복구**: 사용자가 티켓 예약 가능
- ✅ **주문 처리 정상화**: 결제 플로우 완전 동작
- ✅ **이벤트 발행 안정화**: Outbox 패턴 정상 작동
- ✅ **시스템 신뢰성 향상**: 프로덕션 환경 안정화

---

**작성자**: Claude (AI Assistant)  
**검증자**: Traffic Tacos 개발팀  
**상태**: ✅ 해결 완료 및 프로덕션 배포 완료

