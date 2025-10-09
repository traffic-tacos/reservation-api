# 2025-10-09 Reservation API 배포 이슈 요약

## 🚨 이슈 개요

**날짜**: 2025-10-09  
**심각도**: Critical  
**영향**: 전체 예약 기능 불가  
**해결 시간**: 약 2시간  
**배포 버전**: `997d288`

---

## 📊 타임라인

| 시간 | 이벤트 | 상태 |
|-----|--------|------|
| 10:29 | 500 에러 최초 발견 | 🔴 장애 |
| 10:35 | 로그 분석 시작 | 🔍 진단 중 |
| 19:11 | IRSA 인증 수정 배포 | 🟡 부분 개선 |
| 19:35 | Reservation 키 매핑 수정 | 🟡 부분 개선 |
| 21:10 | 전체 엔티티 수정 배포 | 🟢 완전 해결 |
| 21:15 | 기능 검증 완료 | ✅ 정상 |

---

## ❌ 에러 증상

### 클라이언트 응답
```json
{
  "error": {
    "code": "RESERVATION_ERROR",
    "message": "Failed to create reservation",
    "trace_id": ""
  }
}
```

### 서버 로그
```
DynamoDbException: One or more parameter values were invalid: 
Missing the key pk in the item
```

---

## 🔍 근본 원인

### DynamoDB 테이블 스키마와 엔티티 매핑 불일치

**DynamoDB 테이블**:
```
KeySchema:
- pk (HASH)
- sk (RANGE)
```

**Kotlin 엔티티 (잘못됨)**:
```kotlin
@get:DynamoDbPartitionKey
var reservationId: String = ""  // ❌ "pk"로 매핑 안 됨

var eventId: String = ""        // ❌ Sort Key 미지정
```

---

## ✅ 해결 방법

### 1. Reservation 엔티티 수정
```kotlin
@get:DynamoDbPartitionKey
@get:DynamoDbAttribute("pk")
var reservationId: String = ""

@get:DynamoDbSortKey
@get:DynamoDbAttribute("sk")
var eventId: String = ""
```

### 2. Order 엔티티 수정
```kotlin
@get:DynamoDbPartitionKey
@get:DynamoDbAttribute("pk")
var orderId: String = ""

@get:DynamoDbSortKey
@get:DynamoDbAttribute("sk")
var reservationId: String = ""
```

### 3. OutboxEvent 엔티티 수정
```kotlin
@get:DynamoDbPartitionKey
@get:DynamoDbAttribute("pk")
var outboxId: String = ""

@get:DynamoDbSortKey
@get:DynamoDbAttribute("sk")
var eventType: String = ""
```

---

## 🎯 영향받은 컴포넌트

- ✅ **Reservation 생성**: 수정 완료
- ✅ **Order 생성**: 수정 완료
- ✅ **OutboxEvent 발행**: 수정 완료
- ✅ **AWS 인증 (IRSA)**: 수정 완료
- ✅ **gRPC 포트**: 표준화 완료

---

## 📝 관련 문서

- [상세 트러블슈팅 가이드](./troubleshooting/DYNAMODB_SCHEMA_MAPPING_FIX.md)
- [DynamoDB Enhanced Client 공식 문서](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/dynamodb-enhanced-client.html)

---

## 🔒 예방 조치

1. **테스트 강화**: DynamoDB 스키마 검증 테스트 추가
2. **문서화**: 엔티티 매핑 규칙 명시
3. **CI/CD**: 스키마 검증 단계 추가
4. **코드 리뷰**: DynamoDB 엔티티 변경 시 스키마 확인 필수

---

**상태**: ✅ 해결 완료  
**배포 버전**: `997d288` (ECR: `sha256:7de5821e...`)  
**검증**: 프로덕션 환경 정상 동작 확인

