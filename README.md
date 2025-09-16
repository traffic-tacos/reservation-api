# Traffic Tacos Reservation API

고성능 티켓 예매 시스템의 예약 서비스입니다. Kotlin + Spring Boot 3.5.5 기반으로 개발되었으며, 30k RPS 트래픽을 처리할 수 있도록 최적화되었습니다.

## 🏗️ 아키텍처 개요

### 시스템 구성 요소
- **프레임워크**: Spring Boot 3.5.5 + WebFlux
- **언어**: Kotlin 1.9.25
- **데이터베이스**: AWS DynamoDB
- **통신**: gRPC (inventory-api), REST API
- **이벤트**: AWS EventBridge + Outbox 패턴
- **보안**: JWT OIDC + Spring Security
- **관측성**: Micrometer + OTLP + 구조화 로깅
- **복원력**: Resilience4j (Circuit Breaker, Retry, Timeout)

### 프로젝트 구조
```
src/main/kotlin/com/traffictacos/reservation/
├── controller/          # REST API 컨트롤러
├── dto/                 # 요청/응답 DTO
├── service/             # 비즈니스 로직 서비스
├── repository/          # 데이터 접근 레이어
├── grpc/                # gRPC 클라이언트
├── config/              # 설정 클래스들
├── security/            # 보안 설정
├── observability/       # 관측성 설정 (메트릭, 로깅, 트레이싱)
├── performance/         # 성능 최적화 설정
├── resilience/          # 복원력 패턴 구현
├── exception/           # 예외 처리 클래스
├── domain/              # 도메인 모델
└── workflow/            # 비즈니스 워크플로우
```

## 🚀 빠른 시작

### 필수 요구사항
- Java 17+
- Docker & Docker Compose
- AWS CLI (선택사항)

### 로컬 개발 환경 설정

#### 방법 1: 스크립트 사용 (권장)
1. **의존성 서비스 시작**
```bash
./run_local.sh setup
```

2. **애플리케이션 빌드**
```bash
./run_local.sh build
```

3. **애플리케이션 실행**
```bash
./run_local.sh run
```

4. **전체 프로세스 한번에 실행**
```bash
./run_local.sh start
```

#### 방법 2: Docker Compose 사용
```bash
# 개발 환경 실행 (모니터링 스택 포함)
docker-compose -f docker-compose.dev.yml up -d

# 로그 확인
docker-compose -f docker-compose.dev.yml logs -f reservation-api

# 서비스 중지
docker-compose -f docker-compose.dev.yml down
```

### 환경 변수 설정
```bash
# AWS 설정
export AWS_REGION=ap-northeast-2
export DYNAMODB_ENDPOINT=http://localhost:8000
export EVENTBRIDGE_ENDPOINT=http://localhost:4566

# 서비스 엔드포인트
export INVENTORY_GRPC_ADDRESS=localhost:9090
export JWT_ISSUER_URI=http://localhost:8080/auth/realms/traffic-tacos

# 관측성
export OTLP_ENDPOINT=http://localhost:4318/v1/metrics
```

## 📋 API 엔드포인트

### 예약 생성
```http
POST /v1/reservations
Authorization: Bearer <JWT>
Idempotency-Key: <uuid>
Content-Type: application/json

{
  "event_id": "evt_2025_1001",
  "qty": 2,
  "seat_ids": ["A-12", "A-13"],
  "user_id": "u123",
  "reservation_token": "rtkn_xyz789"
}
```

**응답:**
```json
{
  "reservation_id": "rsv_abc123",
  "hold_expires_at": "2024-01-01T12:05:00Z"
}
```

### 예약 확정
```http
POST /v1/reservations/{reservationId}/confirm
Authorization: Bearer <JWT>
Idempotency-Key: <uuid>
Content-Type: application/json

{
  "payment_intent_id": "pay_xyz789"
}
```

**응답:**
```json
{
  "order_id": "ord_xyz789",
  "status": "CONFIRMED"
}
```

### 예약 취소
```http
POST /v1/reservations/{reservationId}/cancel
Authorization: Bearer <JWT>
Idempotency-Key: <uuid>
```

**응답:**
```json
{
  "status": "CANCELLED"
}
```

### 예약 조회
```http
GET /v1/reservations/{reservationId}
Authorization: Bearer <JWT>
```

**응답:**
```json
{
  "reservation_id": "rsv_abc123",
  "status": "HOLD|CONFIRMED|CANCELLED",
  "hold_expires_at": "2024-01-01T12:05:00Z"
}
```

### 에러 응답 포맷
```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "human readable message",
    "trace_id": "..."
  }
}
```

### 에러 코드 표
| 코드 | HTTP | 설명 |
|---|---|---|
| `UNAUTHENTICATED` | 401 | JWT 누락/만료 |
| `FORBIDDEN` | 403 | 권한 부족/허용 전 입장 |
| `RATE_LIMITED` | 429 | 레이트 초과 |
| `IDEMPOTENCY_REQUIRED` | 400 | 멱등성 키 누락 |
| `IDEMPOTENCY_CONFLICT` | 409 | 동일 키 + 다른 요청 |
| `RESERVATION_EXPIRED` | 409 | 홀드 만료 |
| `PAYMENT_NOT_APPROVED` | 412 | 결제 승인 전 |
| `INVENTORY_CONFLICT` | 409 | 재고 부족/충돌 |
| `UPSTREAM_TIMEOUT` | 504 | 백엔드 타임아웃 |

## 🔧 구성 설정

### 프로파일별 설정
- **local**: 로컬 개발용 (디버그 로깅, H2 데이터베이스)
- **dev**: 개발 환경용
- **prod**: 운영 환경용 (최적화된 설정)

### 주요 설정 파일
- `application.properties`: 공통 설정
- `application-prod.yml`: 운영 환경 설정
- `logback-spring.xml`: 로깅 설정

## 🧪 테스트 실행

### 단위 테스트
```bash
./gradlew test
```

### 통합 테스트
```bash
./gradlew integrationTest
```

### 성능 테스트
```bash
./gradlew jmeterRun
```

## 📊 모니터링 및 관측성

### 메트릭 엔드포인트
- **Health Check**: `GET /actuator/health`
- **메트릭**: `GET /actuator/metrics`
- **Prometheus**: `GET /actuator/prometheus`
- **OpenAPI**: `GET /v3/api-docs`
- **Swagger UI**: `GET /swagger-ui.html`

### 구조화 로깅
- **JSON 포맷**: 모든 로그가 JSON으로 출력
- **트레이싱**: OpenTelemetry trace_id 자동 포함
- **비즈니스 메트릭**: 예약 상태별 카운트, 처리 시간
- **보안**: 민감 데이터 마스킹 처리

### 로그 분석
```bash
# 애플리케이션 로그
tail -f logs/reservation-api.log

# JSON 구조화 로그
tail -f logs/reservation-api-json.log

# 특정 예약 ID로 필터링
grep "reservation_id.*rsv_abc123" logs/reservation-api-json.log
```

### 주요 메트릭
- `http.server.requests`: HTTP 요청 메트릭 (P95, P99 지연시간)
- `grpc.client.duration`: gRPC 호출 성능 (inventory-api)
- `reservation.status.total`: 예약 상태별 카운트 (HOLD, CONFIRMED, CANCELLED)
- `service.method.duration`: 서비스 메서드 성능
- `idempotency.requests.total`: 멱등성 처리 메트릭
- `outbox.events.published`: 이벤트 발행 메트릭

### 분산 트레이싱
- **OpenTelemetry**: 자동 계측 및 트레이스 수집
- **Jaeger**: 트레이스 시각화 (로컬: http://localhost:16686)
- **Trace Context**: 요청 간 트레이스 ID 전파

## 🔒 보안

### JWT 인증
- OIDC 호환 JWT 토큰 사용
- `Authorization: Bearer <token>` 헤더 필수
- 자동 토큰 검증 및 권한 추출

### 멱등성 보장
- `Idempotency-Key` 헤더 필수
- DynamoDB 기반 키 저장 (TTL: 5분)
- 중복 요청 자동 방지

## ⚡ 성능 최적화

### 목표 성능
- **P95 지연시간**: < 120ms (확정 제외)
- **에러율**: < 1%
- **30k RPS 처리**: 수평 확장 지원
- **gRPC 타임아웃**: < 250ms (inventory-api 호출)

### 최적화 포인트
- **WebFlux**: 비동기 논블로킹 처리
- **Jackson Afterburner**: JSON 직렬화 최적화
- **커넥션 풀**: gRPC 채널 재사용 및 최적화
- **Resilience4j**: Circuit Breaker, Retry, Timeout 패턴
- **캐싱**: Redis 기반 멱등성 키 캐싱
- **메모리**: JVM 힙 최적화 및 GC 튜닝

### 복원력 패턴
- **Circuit Breaker**: 외부 서비스 장애 격리
- **Retry**: 일시적 오류 자동 재시도
- **Timeout**: 응답 시간 제한
- **Bulkhead**: 리소스 격리
- **Rate Limiting**: 트래픽 제어

## 🏗️ 데이터 모델

### DynamoDB 테이블 구조

#### Reservations 테이블
```javascript
// Primary Key
pk: reservation_id  // "rsv_abc123"
sk: event_id        // "evt_2025_1001"

// Attributes
user_id: "u123"
status: "HOLD|CONFIRMED|CANCELLED"
seat_ids: ["A-12", "A-13"]
quantity: 2
total_price: 50000
hold_expires_at: "2024-01-01T12:05:00Z"
idempotency_key: "uuid-v4"
created_at: "2024-01-01T12:00:00Z"
updated_at: "2024-01-01T12:00:00Z"
```

#### Orders 테이블
```javascript
// Primary Key
pk: order_id        // "ord_xyz789"
sk: reservation_id  // "rsv_abc123"

// Attributes
user_id: "u123"
event_id: "evt_2025_1001"
seat_ids: ["A-12", "A-13"]
total_amount: 50000
status: "CONFIRMED"
payment_intent_id: "pay_xyz789"
created_at: "2024-01-01T12:00:00Z"
```

#### Idempotency 테이블
```javascript
// Primary Key
pk: idempotency_key  // "uuid-v4"

// Attributes
request_hash: "sha256_hash"
response_snapshot: "json_response"
ttl: 1640995200  // 5분 후 만료
```

#### Outbox 테이블
```javascript
// Primary Key
pk: outbox_id  // "outbox_uuid"

// Attributes
type: "reservation.hold.created"
payload: "json_event_data"
status: "PENDING|PUBLISHED|FAILED"
attempts: 0
next_retry_at: "2024-01-01T12:01:00Z"
```

## 🔧 개발 도구

### Proto 파일 컴파일
```bash
./generate_proto.sh
```

### Docker 서비스 관리
```bash
# 서비스 시작
docker-compose -f docker-compose.dev.yml up -d

# 서비스 중지
docker-compose -f docker-compose.dev.yml down
```

#### Docker 이미지 빌드 및 테스트
```bash
# 멀티스테이지 빌드
docker build -t traffictacos/reservation-api:latest .

# 빌드 캐시 없이 새로 빌드
docker build --no-cache -t traffictacos/reservation-api:latest .

# 특정 스테이지만 빌드 (빌드 단계 확인용)
docker build --target builder -t traffictacos/reservation-api:builder .

# 컨테이너 실행 테스트
docker run --rm -p 8080:8080 traffictacos/reservation-api:latest
```

### DynamoDB 테이블 관리
```bash
# 테이블 생성
aws dynamodb create-table --cli-input-json file://dynamodb/tables.json

# 데이터 조회
aws dynamodb scan --table-name reservations
```

## 📚 API 문서

### OpenAPI 3.0 스펙
- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **API 문서**: `http://localhost:8080/v3/api-docs`

### gRPC 프로토콜
```protobuf
// inventory.proto
service Inventory {
  rpc CheckAvailability(CheckAvailabilityRequest) returns (CheckAvailabilityResponse);
  rpc CommitReservation(CommitReservationRequest) returns (CommitReservationResponse);
  rpc ReleaseHold(ReleaseHoldRequest) returns (ReleaseHoldResponse);
}
```

## 🚀 배포

### Docker 빌드 및 배포

#### 로컬 Docker 빌드
```bash
# 이미지 빌드
docker build -t traffictacos/reservation-api:latest .

# 컨테이너 실행
docker run -p 8080:8080 \
  -e AWS_REGION=ap-northeast-2 \
  -e DYNAMODB_ENDPOINT=http://localhost:8000 \
  traffictacos/reservation-api:latest
```

#### Docker Compose 운영 환경
```bash
# 운영 환경용 실행
docker-compose -f docker-compose.prod.yml up -d

# 로그 확인
docker-compose -f docker-compose.prod.yml logs -f

# 서비스 중지
docker-compose -f docker-compose.prod.yml down
```

### Kubernetes 배포
```bash
kubectl apply -f k8s/
```

### 헬름 차트
```bash
helm install reservation-api ./helm/reservation-api
```

### 환경 변수
```bash
# 필수 환경 변수들
AWS_REGION=ap-northeast-2
AWS_ACCESS_KEY_ID=your-access-key-id
AWS_SECRET_ACCESS_KEY=your-secret-access-key
INVENTORY_GRPC_ADDRESS=inventory-service.cluster.local:9090
JWT_ISSUER_URI=https://auth.traffic-tacos.com/auth/realms/traffic-tacos
OTLP_ENDPOINT=http://otel-collector.cluster.local:4318/v1/metrics
```

## 🤝 기여 가이드

### 코드 품질
- Kotlin 코딩 컨벤션 준수
- 단위 테스트 80% 이상 커버리지
- 통합 테스트 필수
- 성능 테스트 검증

### 커밋 메시지
```
feat: 새로운 예약 생성 API 추가
fix: 예약 만료 처리 버그 수정
docs: API 문서 업데이트
test: 단위 테스트 추가
```

## 📞 지원

### 이슈 리포팅
[GitHub Issues](https://github.com/traffictacos/reservation-api/issues)

### 연락처
- **이메일**: dev@traffictacos.com
- **Slack**: #reservation-api

---

## 🎯 개발 로드맵

- [x] 기본 아키텍처 구현
- [x] DynamoDB 통합 (reservations, orders, idempotency, outbox)
- [x] gRPC 클라이언트 (inventory-api 연동)
- [x] REST API 구현 (CRUD + 비즈니스 로직)
- [x] 보안 및 인증 (JWT OIDC)
- [x] 관측성 및 모니터링 (OpenTelemetry, Prometheus)
- [x] 복원력 패턴 (Resilience4j)
- [x] 테스트 코드 (단위/통합/성능)
- [x] 문서화 (API 스펙, 아키텍처)
- [x] 멱등성 처리 (DynamoDB 기반)
- [x] 이벤트 기반 아키텍처 (Outbox 패턴)
- [x] 구조화 로깅 (JSON + 트레이싱)
- [x] 예외 처리 및 에러 응답 표준화
- [ ] 성능 튜닝 (진행 중)
- [ ] 캐시 레이어 추가 (Redis)
- [ ] 분산 트레이싱 개선
- [ ] GraphQL API 지원
- [ ] 다국어 지원

---

*이 프로젝트는 Traffic Tacos의 고성능 티켓 예매 플랫폼의 핵심 컴포넌트입니다.*
