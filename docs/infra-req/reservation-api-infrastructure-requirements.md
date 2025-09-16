# Reservation API Infrastructure Requirements

## Overview

This document specifies the AWS infrastructure requirements for the Traffic Tacos Reservation API service. The service is built with Kotlin + Spring Boot 3.5.5 and is designed to handle 30k RPS with reactive WebFlux architecture.

## Service Information

- **Service Name**: `reservation-api`
- **Environment Prefix**: `traffic-tacos` or as per environment naming convention
- **Region**: `ap-northeast-2` (Asia Pacific - Seoul)
- **Target Performance**: 30k RPS, P95 < 120ms
- **Architecture**: Event-driven microservice with CQRS and Outbox pattern

## Required AWS Resources

### 1. DynamoDB Tables

#### 1.1 Reservations Table
- **Table Name**: `{env}-reservation-reservations`
- **Purpose**: Store reservation data with hold/confirm/cancel status
- **Primary Key**: 
  - **Hash Key (pk)**: `reservation_id` (String)
  - **Range Key (sk)**: `event_id` (String)
- **Billing Mode**: On-Demand (Pay-per-request)
- **Features**:
  - Point-in-time Recovery: Enabled
  - Server-side Encryption: AWS Managed (DynamoDB)
  - DeletionProtection: Enabled (for production)

**Attributes**:
```json
{
  "pk": "reservation_id",
  "sk": "event_id", 
  "user_id": "String",
  "qty": "Number",
  "seat_ids": "List<String>",
  "status": "String",
  "hold_expires_at": "String (ISO-8601)",
  "idempotency_key": "String",
  "created_at": "String (ISO-8601)",
  "updated_at": "String (ISO-8601)"
}
```

**Global Secondary Indexes**: 
- `user-status-index`: user_id (Hash), status (Range)
- `status-created-index`: status (Hash), created_at (Range)

#### 1.2 Orders Table
- **Table Name**: `{env}-reservation-orders`
- **Purpose**: Store confirmed order records linked to reservations
- **Primary Key**:
  - **Hash Key (pk)**: `order_id` (String)
  - **Range Key (sk)**: `reservation_id` (String)
- **Billing Mode**: On-Demand

**Attributes**:
```json
{
  "pk": "order_id",
  "sk": "reservation_id",
  "event_id": "String",
  "user_id": "String", 
  "seat_ids": "List<String>",
  "total_amount": "Number",
  "status": "String",
  "payment_intent_id": "String",
  "created_at": "String (ISO-8601)"
}
```

**Global Secondary Indexes**:
- `user-created-index`: user_id (Hash), created_at (Range)
- `event-created-index`: event_id (Hash), created_at (Range)

#### 1.3 Idempotency Table
- **Table Name**: `{env}-reservation-idempotency`
- **Purpose**: Request deduplication for API idempotency
- **Primary Key**:
  - **Hash Key (pk)**: `idempotency_key` (String)
- **TTL**: Enabled on `ttl` attribute (5 minutes)
- **Billing Mode**: On-Demand

**Attributes**:
```json
{
  "pk": "idempotency_key",
  "request_hash": "String",
  "response_snapshot": "String",
  "ttl": "Number (Unix timestamp)"
}
```

#### 1.4 Outbox Table
- **Table Name**: `{env}-reservation-outbox`
- **Purpose**: Event outbox pattern for reliable event publishing
- **Primary Key**:
  - **Hash Key (pk)**: `outbox_id` (String)
  - **Range Key (sk)**: `aggregate_type#aggregate_id` (String)
- **Billing Mode**: On-Demand

**Attributes**:
```json
{
  "pk": "outbox_id",
  "sk": "aggregate_type#aggregate_id",
  "type": "String",
  "aggregate_type": "String",
  "aggregate_id": "String", 
  "payload": "String (JSON)",
  "status": "String",
  "attempts": "Number",
  "next_retry_at": "String (ISO-8601)",
  "last_error": "String",
  "trace_id": "String",
  "created_at": "String (ISO-8601)",
  "updated_at": "String (ISO-8601)"
}
```

**Global Secondary Indexes**:
- `status-created_at-index`: status (Hash), created_at (Range) - For processing failed events

### 2. EventBridge (Amazon EventBridge)

#### 2.1 Custom Event Bus
- **Bus Name**: `{env}-reservation-events`
- **Purpose**: Isolated event bus for reservation domain events
- **Event Archive**: 
  - Name: `{env}-reservation-events-archive`
  - Retention: 365 days
  - Pattern: All events from reservation service

#### 2.2 Event Rules

**Rule 1: Reservation Created**
- **Name**: `{env}-reservation-created`
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

**Rule 2: Reservation Status Changed**
- **Name**: `{env}-reservation-status-changed`  
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

**Rule 3: Reservation Expiry Scheduler**
- **Name**: `{env}-reservation-expiry-scheduler`
- **Purpose**: Schedule expiry events for held reservations
- **Target**: EventBridge Scheduler or Lambda function

#### 2.3 Dead Letter Queue (SQS)
- **Queue Name**: `{env}-reservation-events-dlq`
- **Purpose**: Store failed event processing attempts
- **Message Retention**: 14 days
- **Visibility Timeout**: 300 seconds
- **Encryption**: SQS Managed SSE

### 3. IAM Roles and Policies

#### 3.1 Application Service Role
- **Role Name**: `{env}-reservation-api-service-role`
- **Trust Policy**: ECS Task or EKS Service Account
- **Attached Policies**:

**DynamoDB Policy**:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:PutItem", 
        "dynamodb:UpdateItem",
        "dynamodb:DeleteItem",
        "dynamodb:Query",
        "dynamodb:Scan",
        "dynamodb:BatchGetItem",
        "dynamodb:BatchWriteItem"
      ],
      "Resource": [
        "arn:aws:dynamodb:ap-northeast-2:*:table/{env}-reservation-*",
        "arn:aws:dynamodb:ap-northeast-2:*:table/{env}-reservation-*/index/*"
      ]
    }
  ]
}
```

**EventBridge Policy**:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "events:PutEvents"
      ],
      "Resource": [
        "arn:aws:events:ap-northeast-2:*:event-bus/{env}-reservation-events"
      ]
    }
  ]
}
```

**CloudWatch Logs Policy**:
```json
{
  "Version": "2012-10-17", 
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream", 
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:ap-northeast-2:*:log-group:/aws/ecs/reservation-api*"
    }
  ]
}
```

#### 3.2 Event Processing Role
- **Role Name**: `{env}-reservation-event-processor-role`
- **Purpose**: For Lambda functions or other services processing reservation events
- **Permissions**: 
  - Read from EventBridge
  - Write to SQS (DLQ)
  - CloudWatch Logs

### 4. CloudWatch Monitoring

#### 4.1 Custom Metrics Namespace
- **Namespace**: `TrafficTacos/Reservation`
- **Metrics**:
  - `ReservationStatusCount` (by status)
  - `APILatency` (by endpoint)
  - `gRPCCallDuration` (by method)
  - `DynamoDBOperationLatency` (by table, operation)

#### 4.2 CloudWatch Alarms

**High Error Rate Alarm**:
- **Metric**: HTTP 5xx error rate > 1%
- **Evaluation**: 2 periods of 5 minutes
- **Action**: SNS notification

**High Latency Alarm**:
- **Metric**: P95 latency > 120ms
- **Evaluation**: 3 periods of 5 minutes
- **Action**: SNS notification

**DynamoDB Throttling Alarm**:
- **Metric**: Read/Write throttle events > 0
- **Evaluation**: 1 period of 5 minutes
- **Action**: SNS notification

### 5. Application Configuration Environment Variables

The application expects these environment variables to be configured:

#### Required Environment Variables
```bash
# AWS Configuration
AWS_REGION=ap-northeast-2

# DynamoDB Table Names
APP_DYNAMODB_TABLES_RESERVATIONS={env}-reservation-reservations
APP_DYNAMODB_TABLES_ORDERS={env}-reservation-orders  
APP_DYNAMODB_TABLES_IDEMPOTENCY={env}-reservation-idempotency
APP_DYNAMODB_TABLES_OUTBOX={env}-reservation-outbox

# EventBridge Configuration  
AWS_EVENTBRIDGE_BUS_NAME={env}-reservation-events

# gRPC Configuration
INVENTORY_GRPC_ADDRESS=inventory-service.{env}.svc.cluster.local:9090

# JWT Authentication
JWT_ISSUER_URI=https://auth.traffic-tacos.com/auth/realms/traffic-tacos

# Observability
OTLP_ENDPOINT=http://otel-collector.{env}.svc.cluster.local:4318/v1/metrics
```

#### Optional Environment Variables
```bash
# Local Development Overrides (not needed for AWS deployment)
DYNAMODB_ENDPOINT=http://localhost:8000  # Only for local development
EVENTBRIDGE_ENDPOINT=http://localhost:4566  # Only for local development

# Performance Tuning
SPRING_PROFILES_ACTIVE=prod
JVM_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC -XX:+UseContainerSupport"
```

### 6. Security Requirements

#### 6.1 Network Security
- **VPC**: Deploy in private subnets
- **Security Groups**: 
  - Inbound: Port 8080 from ALB/NLB only
  - Outbound: HTTPS (443), DynamoDB VPC endpoint, EventBridge
- **VPC Endpoints**: DynamoDB, EventBridge (recommended for cost optimization)

#### 6.2 Encryption
- **Data at Rest**: All DynamoDB tables encrypted with AWS managed keys
- **Data in Transit**: TLS 1.2+ for all communications
- **Secrets**: JWT verification keys in AWS Secrets Manager

#### 6.3 Access Control
- **Principle of Least Privilege**: IAM roles with minimal required permissions
- **Resource-based Policies**: EventBridge and DynamoDB resource policies
- **Cross-Service Access**: Service-to-service authentication via IAM roles

### 7. Cost Optimization

#### 7.1 DynamoDB Optimization
- **On-Demand Billing**: Suitable for unpredictable traffic patterns
- **GSI Projection**: Only project required attributes to reduce costs
- **TTL**: Automatic cleanup of idempotency records

#### 7.2 EventBridge Optimization  
- **Event Filtering**: Precise event patterns to reduce processing costs
- **Archive Compression**: Automatic compression for long-term storage

#### 7.3 CloudWatch Optimization
- **Log Retention**: 30 days for application logs
- **Metric Retention**: Standard CloudWatch retention
- **Custom Metrics**: Only essential business metrics

### 8. Disaster Recovery & Backup

#### 8.1 DynamoDB Backup
- **Point-in-time Recovery**: Enabled for all tables
- **On-demand Backups**: Before major deployments
- **Cross-region Replication**: For production (if required)

#### 8.2 EventBridge
- **Event Archive**: 365-day retention for replay capability
- **Cross-region Replication**: For critical events (if required)

### 9. Deployment Considerations

#### 9.1 Blue-Green Deployment Support
- **Health Checks**: `/actuator/health` endpoint
- **Graceful Shutdown**: 30-second termination grace period
- **Rolling Updates**: Zero-downtime deployment capability

#### 9.2 Scaling Configuration
- **Horizontal Scaling**: Support for multiple instances
- **Connection Pooling**: Optimized DynamoDB and gRPC connections
- **Circuit Breakers**: Resilience4j configuration for fault tolerance

## Implementation Priority

1. **Phase 1 (MVP)**: DynamoDB tables, basic IAM roles
2. **Phase 2**: EventBridge setup, monitoring alarms  
3. **Phase 3**: Advanced monitoring, cost optimization
4. **Phase 4**: Disaster recovery, cross-region setup

## Terraform Module Dependencies

This service requires the following Terraform modules to be deployed:
- `dynamodb` module (for all 4 tables)
- `eventbridge` module (for custom bus and rules)
- `iam` module (for service roles)
- `cloudwatch` module (for alarms and monitoring)

## Validation Commands

After deployment, use these AWS CLI commands to validate the infrastructure:

```bash
# Validate DynamoDB Tables
aws dynamodb list-tables --region ap-northeast-2 | grep reservation

# Validate EventBridge Bus
aws events list-event-buses --region ap-northeast-2 | grep reservation-events

# Validate IAM Roles  
aws iam list-roles --query 'Roles[?contains(RoleName, `reservation`)].RoleName' --output table

# Test DynamoDB Access
aws dynamodb describe-table --table-name {env}-reservation-reservations --region ap-northeast-2
```