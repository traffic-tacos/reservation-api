# EventBridge Module Specification

## Overview

EventBridge 모듈은 Traffic Tacos 프로젝트의 이벤트 기반 아키텍처를 관리합니다. 티켓팅 시스템과 예약 시스템의 이벤트 라우팅, 처리, 아카이빙을 담당하며 마이크로서비스 간 비동기 통신을 지원합니다.

## Architecture

```
EventBridge Module
├── Event Buses
│   ├── ticket-ticket-events (Main Bus)
│   └── ticket-reservation-events (Reservation Bus)
├── Event Rules
│   ├── Ticket Service Rules
│   │   ├── ticket-created (티켓 생성 이벤트)
│   │   └── ticket-status-changed (상태 변경 이벤트)
│   └── Reservation Service Rules
│       ├── reservation-created (예약 생성 이벤트)
│       ├── reservation-status-changed (예약 상태 변경 이벤트)
│       └── reservation-expiry-scheduler (예약 만료 스케줄러)
├── Targets
│   ├── Lambda Functions
│   ├── SQS Queues
│   └── SNS Topics
├── Dead Letter Queue
│   └── ticket-events-dlq
├── Archive
│   └── ticket-events-archive
└── IAM Roles
    ├── Service Role (이벤트 발행)
    └── Target Role (타겟 호출)
```

## Resource Specifications

### Event Buses

#### Main Event Bus
- **이름**: `ticket-ticket-events`
- **ARN**: `arn:aws:events:ap-northeast-2:137406935518:event-bus/ticket-ticket-events`
- **용도**: 티켓 서비스 이벤트 처리
- **장점**: 기본 버스와 분리하여 보안 및 관리 향상

#### Reservation Event Bus
- **이름**: `ticket-reservation-events`
- **ARN**: `arn:aws:events:ap-northeast-2:137406935518:event-bus/ticket-reservation-events`
- **용도**: 예약 서비스 전용 이벤트 버스로 도메인별 이벤트 격리
- **장점**: 마이크로서비스별 이벤트 분리 및 독립적 관리

### Event Rules

#### ticket-created Rule
- **이름**: `ticket-ticket-created`
- **ARN**: `arn:aws:events:ap-northeast-2:137406935518:rule/ticket-ticket-events/ticket-ticket-created`
- **설명**: 티켓 생성 시 발생하는 이벤트 처리
- **Event Pattern**:
```json
{
  "source": ["ticket.service"],
  "detail-type": ["Ticket Created"],
  "detail": {
    "status": ["created"]
  }
}
```
- **타겟**: Lambda function (arn:aws:lambda:ap-northeast-2:137406935518:function:ticket-handler)

#### ticket-status-changed Rule
- **이름**: `ticket-ticket-status-changed`
- **ARN**: `arn:aws:events:ap-northeast-2:137406935518:rule/ticket-ticket-events/ticket-ticket-status-changed`
- **설명**: 티켓 상태 변경 시 발생하는 이벤트 처리
- **Event Pattern**:
```json
{
  "source": ["ticket.service"],
  "detail-type": ["Ticket Status Changed"],
  "detail": {
    "status": ["approved", "rejected", "completed"]
  }
}
```
- **타겟**: Lambda function (arn:aws:lambda:ap-northeast-2:137406935518:function:status-handler)

### Reservation Service Event Rules

#### reservation-created Rule
- **이름**: `ticket-reservation-created`
- **ARN**: `arn:aws:events:ap-northeast-2:137406935518:rule/ticket-ticket-events/ticket-reservation-created`
- **설명**: 예약 생성 시 발생하는 이벤트 처리
- **Event Pattern**:
```json
{
  "source": ["reservation.service"],
  "detail-type": ["Reservation Created"],
  "detail": {
    "status": ["HOLD"]
  }
}
```
- **타겟**: Lambda function (arn:aws:lambda:ap-northeast-2:137406935518:function:reservation-handler)

#### reservation-status-changed Rule
- **이름**: `ticket-reservation-status-changed`
- **ARN**: `arn:aws:events:ap-northeast-2:137406935518:rule/ticket-ticket-events/ticket-reservation-status-changed`
- **설명**: 예약 상태 변경 시 발생하는 이벤트 처리
- **Event Pattern**:
```json
{
  "source": ["reservation.service"],
  "detail-type": ["Reservation Status Changed"],
  "detail": {
    "status": ["CONFIRMED", "CANCELLED", "EXPIRED"]
  }
}
```
- **타겟**: Lambda function (arn:aws:lambda:ap-northeast-2:137406935518:function:reservation-status-handler)

#### reservation-expiry-scheduler Rule
- **이름**: `ticket-reservation-expiry-scheduler`
- **ARN**: `arn:aws:events:ap-northeast-2:137406935518:rule/ticket-ticket-events/ticket-reservation-expiry-scheduler`
- **설명**: 예약 만료 스케줄러 이벤트 처리
- **Event Pattern**:
```json
{
  "source": ["aws.scheduler"],
  "detail-type": ["Scheduled Event"],
  "detail": {
    "event_type": ["reservation-expiry"]
  }
}
```
- **타겟**: Lambda function (arn:aws:lambda:ap-northeast-2:137406935518:function:reservation-expiry-handler)

### Dead Letter Queue (DLQ)
- **이름**: `ticket-events-dlq`
- **ARN**: `arn:aws:sqs:ap-northeast-2:137406935518:ticket-events-dlq`
- **URL**: `https://sqs.ap-northeast-2.amazonaws.com/137406935518/ticket-events-dlq`
- **용도**: 실패한 이벤트 저장 및 재처리
- **설정**:
  - 메시지 보관 기간: 14일
  - 최대 메시지 크기: 256KB
  - SQS managed SSE 활성화
  - 자동 DLQ 연결: 모든 타겟에 기본 적용

### Event Archive
- **이름**: `ticket-ticket-events-archive`
- **ARN**: `arn:aws:events:ap-northeast-2:137406935518:archive/ticket-ticket-events-archive`
- **용도**: 이벤트 이력 보관 및 재처리
- **설정**:
  - 보관 기간: 365일
  - Event Pattern: ticket.service 소스의 모든 이벤트
  - Event Source: ticket-ticket-events 버스

### IAM Roles

#### Service Role
- **이름**: `ticket-eventbridge-service-role`
- **용도**: 애플리케이션에서 EventBridge로 이벤트 발행
- **권한**:
  - events:PutEvents (커스텀 버스)
  - logs:CreateLogGroup, CreateLogStream, PutLogEvents

#### Target Role
- **이름**: `ticket-eventbridge-target-role`
- **용도**: EventBridge에서 다양한 AWS 서비스 호출
- **권한**:
  - lambda:InvokeFunction
  - sqs:SendMessage
  - sns:Publish
  - dynamodb:PutItem, UpdateItem, GetItem (설정 시)

## Configuration Variables

### Required Variables
- `name`: 리소스 접두사 (기본값: "ticket")

### Optional Variables
- `custom_bus_name`: 커스텀 버스 이름 (기본값: "ticket-events")
- `rules`: 이벤트 규칙 목록 (기본값: ticket-created, ticket-status-changed)
- `enable_dlq`: DLQ 활성화 (기본값: true)
- `dlq_retention_days`: DLQ 보관 기간 (기본값: 14일)
- `archive_config`: 아카이브 설정 (기본값: 활성화, 365일 보관)
- `dynamodb_table_arns`: DynamoDB 테이블 ARN 목록

## Usage Examples

### Basic Usage
```hcl
module "eventbridge" {
  source = "./modules/eventbridge"
  dynamodb_table_arns = values(module.dynamodb.table_arns)
}
```

### Custom Configuration
```hcl
module "eventbridge" {
  source = "./modules/eventbridge"
  name   = "production"

  custom_bus_name = "prod-events"
  dlq_retention_days = 30

  rules = [
    {
      name = "order-created"
      description = "주문 생성 이벤트"
      event_pattern = jsonencode({
        source = ["order.service"]
        detail-type = ["Order Created"]
      })
      targets = [
        {
          id = "order-processor"
          arn = "arn:aws:lambda:ap-northeast-2:123456789012:function:order-processor"
          retry_policy = {
            maximum_retry_attempts = 3
            maximum_event_age_in_seconds = 3600
          }
        }
      ]
    }
  ]

  archive_config = {
    enabled = true
    retention_days = 2555  # 7년
    event_pattern = jsonencode({
      source = ["order.service", "ticket.service"]
    })
  }
}
```

### Event Publishing Example
```python
# Python 애플리케이션에서 이벤트 발행
import boto3
import json

eventbridge = boto3.client('events', region_name='ap-northeast-2')

response = eventbridge.put_events(
    Entries=[
        {
            'Source': 'ticket.service',
            'DetailType': 'Ticket Created',
            'Detail': json.dumps({
                'ticket_id': 'ticket-123',
                'user_id': 'user-456',
                'status': 'created',
                'created_at': '2024-01-01T10:00:00Z'
            }),
            'EventBusName': 'ticket-ticket-events'  # 실제 배포된 버스 이름
        }
    ]
)
```

## Event Patterns

### Standard Event Structure
```json
{
  "version": "0",
  "id": "event-id",
  "detail-type": "Event Type",
  "source": "service.name",
  "account": "123456789012",
  "time": "2024-01-01T10:00:00Z",
  "region": "ap-northeast-2",
  "detail": {
    "custom": "data"
  }
}
```

### Ticket Events
```json
{
  "source": "ticket.service",
  "detail-type": "Ticket Created",
  "detail": {
    "ticket_id": "string",
    "user_id": "string",
    "status": "created|approved|rejected|completed",
    "priority": "low|medium|high|urgent",
    "created_at": "timestamp",
    "metadata": {}
  }
}
```

## Outputs

| Output | Description | Type |
|--------|-------------|------|
| `event_bus_name` | 커스텀 버스 이름 | string |
| `event_bus_arn` | 커스텀 버스 ARN | string |
| `rule_arns` | 이벤트 규칙 ARN 맵 | map(string) |
| `rule_names` | 이벤트 규칙 이름 맵 | map(string) |
| `dlq_url` | DLQ URL | string |
| `dlq_arn` | DLQ ARN | string |
| `archive_arn` | 아카이브 ARN | string |
| `service_role_arn` | 서비스 역할 ARN | string |
| `target_role_arn` | 타겟 역할 ARN | string |
| `additional_bus_arns` | 추가 이벤트 버스 ARN 맵 | map(string) |
| `additional_bus_names` | 추가 이벤트 버스 이름 맵 | map(string) |
| `reservation_bus_arn` | Reservation 이벤트 버스 ARN | string |
| `reservation_bus_name` | Reservation 이벤트 버스 이름 | string |

## Monitoring & Observability

### CloudWatch Metrics
- **SuccessfulInvocations**: 성공한 호출 수
- **FailedInvocations**: 실패한 호출 수 (알람 설정됨)
- **TriggeredRules**: 트리거된 규칙 수

### CloudWatch Logs
- **Log Group**: `/aws/events/ticket-eventbridge`
- **보관 기간**: 30일
- **로그 내용**: 이벤트 처리 상세 정보

### Alarms (Deployed)
- **Failed Invocation Alarms**:
  - `ticket-ticket-created-failed-invocations`
  - `ticket-ticket-status-changed-failed-invocations`
- **설정**:
  - Threshold: 0 (실패 발생 시 즉시 알림)
  - Evaluation Periods: 2
  - Period: 300초 (5분)
  - Namespace: AWS/Events

## Security Considerations

1. **이벤트 격리**: 커스텀 버스를 통한 애플리케이션별 이벤트 분리
2. **최소 권한**: 각 역할은 필요한 최소 권한만 보유
3. **암호화**: 전송 중 및 저장 시 암호화 (AWS 관리)
4. **감사**: CloudTrail을 통한 모든 API 호출 로깅

## Cost Optimization

1. **이벤트 필터링**: 정확한 Event Pattern으로 불필요한 호출 방지
2. **DLQ 보관 기간**: 적절한 보관 기간 설정
3. **아카이브**: 필요한 이벤트만 선별적 아카이브
4. **타겟 최적화**: 배치 처리 가능한 타겟 사용

## Best Practices

### Event Design
1. **일관된 스키마**: 모든 이벤트에 표준 스키마 적용
2. **버전 관리**: detail-type에 버전 정보 포함
3. **멱등성**: 중복 이벤트 처리 고려
4. **타임스탬프**: UTC 기준 ISO 8601 형식 사용

### Error Handling
1. **DLQ 모니터링**: 정기적인 DLQ 메시지 확인
2. **재시도 정책**: 적절한 재시도 횟수 및 간격 설정
3. **Circuit Breaker**: 타겟 서비스 장애 시 보호 메커니즘

## Troubleshooting

### 일반적인 문제

1. **이벤트가 타겟에 도달하지 않음**
   - Event Pattern 검증
   - 타겟 권한 확인
   - CloudWatch Logs 확인

2. **DLQ에 메시지 누적**
   - 타겟 서비스 상태 확인
   - 재시도 정책 조정
   - 이벤트 구조 검증

3. **높은 비용**
   - 불필요한 이벤트 발생 확인
   - Event Pattern 최적화
   - 아카이브 정책 검토