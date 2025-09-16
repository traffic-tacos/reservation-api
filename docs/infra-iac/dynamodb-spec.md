# DynamoDB Module Specification

## Overview

DynamoDB 모듈은 Traffic Tacos 프로젝트의 NoSQL 데이터베이스 인프라를 관리합니다. 티켓팅 시스템과 예약 시스템에 필요한 테이블들과 관련 IAM 권한, 모니터링을 포함합니다.

## Architecture

```
DynamoDB Module
├── Tables
│   ├── Ticket Service
│   │   ├── ticket-tickets (tickets 테이블)
│   │   └── ticket-ticket-events (이벤트 테이블)
│   └── Reservation Service
│       ├── ticket-reservation-reservations (예약 테이블)
│       ├── ticket-reservation-orders (주문 테이블)
│       ├── ticket-reservation-idempotency (멱등성 테이블)
│       └── ticket-reservation-outbox (아웃박스 테이블)
├── IAM Roles
│   ├── Application Role (읽기/쓰기 권한)
│   ├── ReadOnly Role (읽기 전용 권한)
│   └── Reservation Service Role (예약 API 전용)
├── Monitoring
│   ├── CloudWatch Alarms (throttling 감지)
│   └── Metrics (read/write throttle events)
└── Security
    ├── Encryption at Rest
    └── Point-in-time Recovery
```

## Resource Specifications

### Tables

#### tickets 테이블
- **이름**: `ticket-tickets`
- **ARN**: `arn:aws:dynamodb:ap-northeast-2:137406935518:table/ticket-tickets`
- **Primary Key**: `ticket_id` (String)
- **Billing Mode**: Pay-per-request
- **Features**:
  - Point-in-time recovery 활성화
  - 서버 측 암호화 활성화 (AWS managed key)
  - DynamoDB Streams 비활성화

**Global Secondary Indexes:**
- `user-index`: user_id를 hash key로 사용
- `status-index`: status를 hash key로 사용

**Attributes:**
```json
{
  "ticket_id": "String",
  "user_id": "String",
  "status": "String"
}
```

#### users 테이블
- **이름**: `ticket-users`
- **ARN**: `arn:aws:dynamodb:ap-northeast-2:137406935518:table/ticket-users`
- **Primary Key**: `user_id` (String)
- **Billing Mode**: Pay-per-request

**Global Secondary Indexes:**
- `email-index`: email을 hash key로 사용

**Attributes:**
```json
{
  "user_id": "String",
  "email": "String"
}
```

#### ticket-events 테이블
- **이름**: `ticket-ticket-events`
- **ARN**: `arn:aws:dynamodb:ap-northeast-2:137406935518:table/ticket-ticket-events`
- **Primary Key**:
  - Hash Key: `pk` (String)
  - Range Key: `sk` (String)
- **Billing Mode**: Pay-per-request
- **Features**:
  - Point-in-time recovery 활성화
  - 서버 측 암호화 활성화 (AWS managed key)

### Reservation Service Tables

#### reservation-reservations 테이블
- **이름**: `ticket-reservation-reservations`
- **ARN**: `arn:aws:dynamodb:ap-northeast-2:137406935518:table/ticket-reservation-reservations`
- **Primary Key**:
  - Hash Key: `pk` (String)
  - Range Key: `sk` (String)
- **Billing Mode**: Pay-per-request
- **Features**:
  - Point-in-time recovery 활성화
  - 서버 측 암호화 활성화 (AWS managed key)

**Global Secondary Indexes:**
- `GSI1`: gsi1pk (hash), gsi1sk (range) - 다양한 쿼리 패턴 지원

#### reservation-orders 테이블
- **이름**: `ticket-reservation-orders`
- **ARN**: `arn:aws:dynamodb:ap-northeast-2:137406935518:table/ticket-reservation-orders`
- **Primary Key**:
  - Hash Key: `pk` (String)
  - Range Key: `sk` (String)
- **Billing Mode**: Pay-per-request
- **Features**:
  - Point-in-time recovery 활성화
  - 서버 측 암호화 활성화 (AWS managed key)

**Global Secondary Indexes:**
- `GSI1`: gsi1pk (hash), gsi1sk (range) - 주문 상태별 조회

#### reservation-idempotency 테이블
- **이름**: `ticket-reservation-idempotency`
- **ARN**: `arn:aws:dynamodb:ap-northeast-2:137406935518:table/ticket-reservation-idempotency`
- **Primary Key**: `pk` (String)
- **Billing Mode**: Pay-per-request
- **Features**:
  - TTL 활성화 (ttl 속성 기반)
  - Point-in-time recovery 활성화
  - 서버 측 암호화 활성화 (AWS managed key)
- **용도**: API 요청 멱등성 보장, 중복 요청 방지

#### reservation-outbox 테이블
- **이름**: `ticket-reservation-outbox`
- **ARN**: `arn:aws:dynamodb:ap-northeast-2:137406935518:table/ticket-reservation-outbox`
- **Primary Key**:
  - Hash Key: `pk` (String)
  - Range Key: `sk` (String)
- **Billing Mode**: Pay-per-request
- **Features**:
  - Point-in-time recovery 활성화
  - 서버 측 암호화 활성화 (AWS managed key)
- **용도**: 이벤트 발행을 위한 아웃박스 패턴 구현

### IAM Roles

#### Application Role
- **이름**: `ticket-dynamodb-application-role`
- **ARN**: Available via module output `application_role_arn`
- **용도**: 애플리케이션에서 DynamoDB 테이블에 대한 전체 액세스
- **권한**:
  - GetItem, PutItem, UpdateItem, DeleteItem
  - Query, Scan, BatchGetItem, BatchWriteItem
  - 모든 GSI에 대한 Query, Scan 권한

#### ReadOnly Role
- **이름**: `ticket-dynamodb-readonly-role`
- **ARN**: Available via module output `readonly_role_arn`
- **용도**: 읽기 전용 접근 (분석, 모니터링 등)
- **권한**:
  - GetItem, Query, Scan, BatchGetItem
  - DescribeTable
  - 모든 GSI에 대한 Query, Scan 권한

#### Reservation Service Role
- **이름**: `ticket-reservation-api-service-role`
- **ARN**: `arn:aws:iam::137406935518:role/ticket-reservation-api-service-role`
- **용도**: Reservation API 전용 역할 (ECS/EKS 환경)
- **권한**:
  - Reservation 테이블에 대한 전체 DynamoDB 액세스
  - EventBridge 이벤트 발행 권한 (reservation-events 버스)
  - CloudWatch Logs 쓰기 권한

**Trust Policy:**
- ECS Tasks (ecs-tasks.amazonaws.com)
- EKS Service Account (OIDC provider 기반)

### CloudWatch Monitoring

#### Alarms (Deployed)
- **Read Throttle Alarms**:
  - `ticket-tickets-read-throttle`
  - `ticket-users-read-throttle`
- **Write Throttle Alarms**:
  - `ticket-tickets-write-throttle`
  - `ticket-users-write-throttle`

**설정:**
- Threshold: 0 (제한 발생 시 즉시 알림)
- Evaluation Periods: 2
- Period: 300초 (5분)
- Namespace: AWS/DynamoDB

## Configuration Variables

### Required Variables
- `name`: 리소스 접두사 (기본값: "ticket")

### Optional Variables
- `tables`: 테이블 구성 목록 (기본값: tickets, users 테이블)
- `enable_point_in_time_recovery`: PITR 활성화 (기본값: true)
- `enable_deletion_protection`: 삭제 보호 (기본값: false)
- `server_side_encryption`: 서버 측 암호화 (기본값: true)
- `kms_key_id`: KMS 키 ID (선택사항)

## Usage Examples

### Basic Usage
```hcl
module "dynamodb" {
  source = "./modules/dynamodb"
}
```

### Custom Configuration
```hcl
module "dynamodb" {
  source = "./modules/dynamodb"
  name   = "production"

  enable_deletion_protection = true
  kms_key_id = "arn:aws:kms:ap-northeast-2:123456789012:key/12345678-1234-1234-1234-123456789012"

  tables = [
    {
      name     = "tickets"
      hash_key = "ticket_id"
      billing_mode = "PROVISIONED"
      read_capacity = 10
      write_capacity = 10
      attributes = [
        {
          name = "ticket_id"
          type = "S"
        }
      ]
    }
  ]
}
```

### Application Integration
```hcl
# EC2 인스턴스에 DynamoDB 접근 권한 부여
resource "aws_iam_instance_profile" "app_profile" {
  name = "app-profile"
  role = module.dynamodb.application_role_name
}

resource "aws_instance" "app_server" {
  # ... other configuration
  iam_instance_profile = aws_iam_instance_profile.app_profile.name
}
```

## Outputs

| Output | Description | Type |
|--------|-------------|------|
| `table_names` | 생성된 테이블 이름 맵 | map(string) |
| `table_arns` | 테이블 ARN 맵 | map(string) |
| `table_stream_arns` | 스트림 ARN 맵 | map(string) |
| `application_role_arn` | 애플리케이션 역할 ARN | string |
| `readonly_role_arn` | 읽기 전용 역할 ARN | string |
| `application_role_name` | 애플리케이션 역할 이름 | string |
| `readonly_role_name` | 읽기 전용 역할 이름 | string |
| `reservation_service_role_arn` | Reservation API 서비스 역할 ARN | string |
| `reservation_service_role_name` | Reservation API 서비스 역할 이름 | string |
| `reservation_tables` | Reservation API 테이블 정보 맵 | map(object) |

## Security Considerations

1. **암호화**: 모든 테이블은 기본적으로 서버 측 암호화가 활성화됨
2. **최소 권한 원칙**: IAM 역할은 필요한 최소 권한만 부여
3. **네트워크 격리**: DynamoDB는 VPC 엔드포인트 사용 권장 (추후 구현 예정)
4. **감사**: CloudTrail을 통한 API 호출 로깅

## Cost Optimization

1. **Pay-per-request 모드**: 예측 불가능한 워크로드에 적합
2. **Point-in-time Recovery**: 필요시에만 활성화
3. **테이블 삭제 보호**: 운영 환경에서만 활성화 권장

## Troubleshooting

### 일반적인 문제

1. **Throttling 발생**
   - CloudWatch 알람 확인
   - 프로비저닝 모드로 변경 고려
   - 애플리케이션의 재시도 로직 구현

2. **권한 문제**
   - IAM 역할이 올바르게 연결되었는지 확인
   - 정책 문서의 리소스 ARN 확인

3. **GSI 성능 이슈**
   - Query 패턴 최적화
   - Hot partition 방지를 위한 키 설계 검토