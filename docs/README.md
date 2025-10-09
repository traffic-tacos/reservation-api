# Reservation API 문서

Traffic Tacos MSA 플랫폼의 예약 서비스 문서 모음입니다.

---

## 📚 문서 목록

### 배포 및 운영

- **[2025-10-09 배포 이슈 요약](./DEPLOYMENT_ISSUE_2025_10_09.md)**  
  DynamoDB 스키마 매핑 문제 해결 요약

### 트러블슈팅

- **[DynamoDB Schema Mapping 문제 해결](./troubleshooting/DYNAMODB_SCHEMA_MAPPING_FIX.md)**  
  DynamoDB 엔티티 pk/sk 매핑 이슈 상세 가이드
  - 근본 원인 분석
  - 단계별 해결 과정
  - 배운 점 및 예방 조치

---

## 🔧 개발 가이드

### DynamoDB 엔티티 작성 규칙

**복합 키 테이블 (pk/sk)**:
```kotlin
@DynamoDbBean
data class YourEntity(
    @get:DynamoDbPartitionKey
    @get:DynamoDbAttribute("pk")  // ⚠️ 필수: 명시적 매핑
    var yourId: String = "",
    
    @get:DynamoDbSortKey           // ⚠️ 필수: Sort Key 지정
    @get:DynamoDbAttribute("sk")   // ⚠️ 필수: 명시적 매핑
    var yourSortKey: String = "",
    
    // 일반 속성들
    var data: String = ""
)
```

### AWS 인증 설정

**로컬 개발**:
```properties
# application-local.properties
aws.region=ap-northeast-2
aws.profile=tacos
```

**Kubernetes (프로덕션)**:
```properties
# application.properties
aws.region=ap-northeast-2
# aws.profile 미설정 → IRSA 자동 사용
```

---

## 🚀 배포 프로세스

### 자동 배포 (CI/CD)

```
코드 푸시 (main 브랜치)
  ↓
GitHub Actions 트리거
  ↓
Gradle 빌드 + 테스트
  ↓
Docker 이미지 생성
  ↓
ECR 푸시 (latest 태그)
  ↓
ArgoCD 자동 동기화
  ↓
Kubernetes 배포
```

### 수동 배포 (긴급)

```bash
# 1. 이미지 확인
aws ecr describe-images \
  --repository-name traffic-tacos-reservation-api \
  --region ap-northeast-2

# 2. Pod 재시작
kubectl rollout restart deployment/reservation-api -n tacos-app

# 3. 배포 상태 확인
kubectl rollout status deployment/reservation-api -n tacos-app

# 4. Pod 로그 확인
kubectl logs -n tacos-app -l app=reservation-api --tail=50 -f
```

---

## 📊 모니터링

### 핵심 지표

- **응답 시간**: P95 < 120ms (예약 확정 제외)
- **에러율**: < 1%
- **가용성**: 99.9% SLA
- **DynamoDB 스로틀링**: 0건 목표

### 로그 조회

```bash
# 실시간 로그
kubectl logs -n tacos-app -l app=reservation-api -f

# 에러 로그만
kubectl logs -n tacos-app -l app=reservation-api | grep ERROR

# gRPC 호출 추적
kubectl logs -n tacos-app -l app=reservation-api | grep "CreateReservation"

# DynamoDB 에러
kubectl logs -n tacos-app -l app=reservation-api | grep "DynamoDb"
```

---

## 🔗 관련 리소스

### 내부 문서
- [Traffic Tacos 아키텍처 가이드](../../../gateway-api/docs/PRESENTATION_FINAL_V3.md)
- [API 계약 정의](../../../gateway-api/docs/API_CONTRACTS.md)

### 외부 문서
- [AWS DynamoDB Enhanced Client](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/dynamodb-enhanced-client.html)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [Spring Boot WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [gRPC Kotlin](https://grpc.io/docs/languages/kotlin/)

---

## 📞 문의

문제가 발생하거나 질문이 있으시면:
1. 먼저 [트러블슈팅 가이드](./troubleshooting/)를 확인하세요
2. 이슈가 계속되면 Slack `#traffic-tacos-dev` 채널에 문의
3. 긴급한 경우 on-call 엔지니어에게 연락

---

**마지막 업데이트**: 2025-10-09  
**관리자**: Traffic Tacos 개발팀

