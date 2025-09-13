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
├── observability/       # 관측성 설정
└── domain/              # 도메인 모델
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

### 예약 확정
```http
POST /v1/reservations/{reservationId}/confirm
Authorization: Bearer <JWT>
Idempotency-Key: <uuid>
```

### 예약 취소
```http
POST /v1/reservations/{reservationId}/cancel
Authorization: Bearer <JWT>
Idempotency-Key: <uuid>
```

### 예약 조회
```http
GET /v1/reservations/{reservationId}
Authorization: Bearer <JWT>
```

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

### 로그 분석
```bash
# 애플리케이션 로그
tail -f logs/reservation-api.log

# JSON 구조화 로그
tail -f logs/reservation-api-json.log
```

### 주요 메트릭
- `http.server.requests`: HTTP 요청 메트릭
- `grpc.call.duration`: gRPC 호출 성능
- `reservation.status.total`: 예약 상태별 카운트
- `service.method.duration`: 서비스 메서드 성능

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

### 최적화 포인트
- WebFlux 기반 비동기 처리
- Jackson Afterburner 적용
- 커넥션 풀 최적화
- Resilience4j 복원력 패턴

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
- [x] DynamoDB 통합
- [x] gRPC 클라이언트
- [x] REST API 구현
- [x] 보안 및 인증
- [x] 관측성 및 모니터링
- [x] 복원력 패턴
- [x] 테스트 코드
- [x] 문서화
- [ ] 성능 튜닝 (진행 중)
- [ ] 캐시 레이어 추가
- [ ] 분산 트레이싱 개선
- [ ] GraphQL API 지원

---

*이 프로젝트는 Traffic Tacos의 고성능 티켓 예매 플랫폼의 핵심 컴포넌트입니다.*
