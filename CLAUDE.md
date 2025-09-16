# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

This is the **reservation-api** service, part of the Traffic Tacos high-performance ticket reservation platform designed to handle **30k RPS** traffic. The reservation service is responsible for:

- **Reservation Management**: 60-second hold periods with automatic expiry
- **Order Processing**: Confirmed reservations after payment approval  
- **Idempotency**: Request deduplication with 5-minute TTL
- **Event Publishing**: Outbox pattern for reliable event delivery

### Architecture Position
- **Framework**: Spring Boot 3.5.5 + WebFlux (reactive)
- **Role**: Receives requests from api-gateway (Go + Fiber)
- **Integrations**: gRPC calls to inventory-api (Go + Echo)
- **Events**: Publishes to worker service via EventBridge/SQS

### Performance Requirements
- **P95 Latency**: < 120ms (excluding confirmation operations)
- **Error Rate**: < 1%
- **Throughput**: 500-1,500 RPS for reservation creation
- **Timeout**: 250ms for gRPC calls to inventory-api

## Development Commands

### Building and Running
- **Build**: `./gradlew clean build` or `./run_local.sh build`
- **Run locally**: `./run_local.sh start` (sets up Docker services, builds, and runs)
- **Run tests**: `./gradlew test` or `./run_local.sh test`
- **Integration tests**: `./gradlew integrationTest`
- **Performance tests**: `./gradlew jmeterRun`

### Quick Setup Commands
- **Full setup**: `./run_local.sh start` - Complete local development environment
- **Infrastructure only**: `./run_local.sh setup` - Start Docker services and create DynamoDB tables
- **Stop services**: `./run_local.sh stop` - Clean up Docker containers

### gRPC Proto Generation
- **Generate proto files**: `./generate_proto.sh`

## Architecture Overview

This is a high-performance Kotlin + Spring Boot 3.5.5 reservation API designed for 30k RPS traffic handling.

### Technology Stack
- **Framework**: Spring Boot 3.5.5 with WebFlux (reactive)
- **Language**: Kotlin 1.9.25 with Java 17
- **Database**: AWS DynamoDB (DynamoDB Local for development)
- **Communication**: gRPC (inventory-api), REST API
- **Events**: AWS EventBridge + Outbox pattern
- **Security**: JWT OIDC + Spring Security
- **Observability**: Micrometer + OTLP + structured logging
- **Resilience**: Resilience4j (Circuit Breaker, Retry, Timeout)

### Project Structure
```
src/main/kotlin/com/traffictacos/reservation/
├── controller/          # REST API controllers
├── dto/                 # Request/response DTOs
├── service/             # Business logic services
├── repository/          # Data access layer (DynamoDB)
├── grpc/                # gRPC client for inventory-api
├── config/              # Configuration classes
├── domain/              # Domain models
└── security/            # Security configuration
```

### Core Domain Models (DynamoDB)
- **reservations**: Primary reservation data with composite key (pk=reservation_id, sk=metadata)
- **orders**: Order records linked to confirmed reservations
- **idempotency**: Request deduplication with TTL (5 minutes)
- **outbox**: Event publishing with retry logic

### API Endpoints (Contract-Based Design)
- `POST /v1/reservations` - Create reservation (requires Idempotency-Key header)
- `POST /v1/reservations/{id}/confirm` - Confirm reservation after payment  
- `POST /v1/reservations/{id}/cancel` - Cancel reservation
- `GET /v1/reservations/{id}` - Get reservation details

**Authentication**: JWT Bearer token (except public endpoints)  
**Idempotency**: All state-changing operations require `Idempotency-Key: <uuid-v4>` header  
**Timeouts**: 600ms max for API responses, 250ms for gRPC calls

### gRPC Integration
The service integrates with inventory-api via gRPC for:
- CheckAvailability
- CommitReservation  
- ReleaseHold

Timeouts: 250ms for all calls, no retries for CommitReservation.

### Key Features & Design Patterns
- **Idempotency**: All state-changing operations require Idempotency-Key header (5-min TTL)
- **Reservation Expiry**: 60-second hold period managed via EventBridge Scheduler  
- **Event Publishing**: Outbox pattern for reliable event delivery to worker service
- **Circuit Breaker**: Resilience4j for gRPC calls (30% failure threshold, 30s open state)
- **JWT Authentication**: OIDC-compliant token validation
- **Structured Logging**: JSON format with OpenTelemetry trace correlation
- **Performance Monitoring**: Custom metrics for reservation status, gRPC calls, HTTP requests

### Error Handling
- **IDEMPOTENCY_REQUIRED** (400): Missing Idempotency-Key header
- **IDEMPOTENCY_CONFLICT** (409): Same key with different request body
- **RESERVATION_EXPIRED** (409): 60-second hold period expired
- **INVENTORY_CONFLICT** (409): Seat unavailable or oversold
- **UPSTREAM_TIMEOUT** (504): gRPC call to inventory-api timeout

### Performance Targets
- P95 latency < 120ms (excluding confirmation operations)
- Error rate < 1%
- Support for 30k RPS with horizontal scaling

### Local Development Environment
The `run_local.sh` script provides a complete local development setup with:
- DynamoDB Local (port 8000)
- LocalStack for AWS services (port 4566)
- OpenTelemetry Collector (ports 4317/4318)

### Testing Strategy
- Unit tests with JUnit 5 and Kotlin test
- Integration tests with Testcontainers (DynamoDB Local, gRPC stubs)
- Performance tests with JMeter integration

### Security Configuration
- Spring Security Resource Server with JWT OIDC
- Automatic token validation and user context extraction
- No sensitive data logging enforcement

### Observability
- Prometheus metrics at `/actuator/prometheus`
- Health checks at `/actuator/health`
- OTLP tracing with distributed trace correlation
- Custom business metrics for reservation workflows

## Infrastructure Resources

### AWS Resources Management
Infrastructure is managed by Terraform and documented in `docs/infra-iac/`. All infrastructure specs are manually created from Terraform definitions and kept in sync.

**IMPORTANT RULE**: When working with AWS resources, always check the actual deployed resource names using AWS CLI commands, as Terraform may generate names with prefixes or suffixes that differ from the specs.

### Infrastructure Documentation
- `docs/infra-iac/dynamodb-spec.md` - DynamoDB tables, indexes, and IAM roles
- `docs/infra-iac/eventbridge-spec.md` - EventBridge custom bus, rules, and targets

### Expected AWS Resources

#### DynamoDB Tables (from docs/infra-iac/dynamodb-spec.md)
- `ticket-tickets` - Main tickets table with GSI: user-index, status-index
- `ticket-users` - User data table with GSI: email-index
- **For this service**: `reservations`, `orders`, `idempotency`, `outbox` tables

#### EventBridge (from docs/infra-iac/eventbridge-spec.md)
- Custom event bus: `ticket-ticket-events`
- Rules: `ticket-ticket-created`, `ticket-ticket-status-changed`
- Dead Letter Queue: `ticket-events-dlq`
- Archive: `ticket-ticket-events-archive`

#### IAM Roles
- `ticket-dynamodb-application-role` - Full DynamoDB access
- `ticket-dynamodb-readonly-role` - Read-only access
- `ticket-eventbridge-service-role` - Event publishing
- `ticket-eventbridge-target-role` - Target invocation

### AWS CLI Commands for Resource Discovery

#### DynamoDB Tables
```bash
# List all DynamoDB tables
aws dynamodb list-tables --region ap-northeast-2

# Get table details
aws dynamodb describe-table --table-name <table-name> --region ap-northeast-2

# List GSI for a table
aws dynamodb describe-table --table-name <table-name> --region ap-northeast-2 --query 'Table.GlobalSecondaryIndexes[].IndexName'
```

#### EventBridge
```bash
# List custom event buses
aws events list-event-buses --region ap-northeast-2

# List rules for a bus
aws events list-rules --event-bus-name <bus-name> --region ap-northeast-2

# Get rule details
aws events describe-rule --name <rule-name> --event-bus-name <bus-name> --region ap-northeast-2
```

#### IAM Roles
```bash
# List IAM roles with ticket prefix
aws iam list-roles --query 'Roles[?starts_with(RoleName, `ticket`)].RoleName' --output table

# Get role details
aws iam get-role --role-name <role-name>

# List attached policies
aws iam list-attached-role-policies --role-name <role-name>
```

#### SQS (for DLQ)
```bash
# List queues
aws sqs list-queues --region ap-northeast-2

# Get queue attributes
aws sqs get-queue-attributes --queue-url <queue-url> --attribute-names All --region ap-northeast-2
```

### Development Environment Variables
When working with AWS resources locally or in development:

```bash
# For local development (using LocalStack)
export AWS_REGION=ap-northeast-2
export DYNAMODB_ENDPOINT=http://localhost:8000
export EVENTBRIDGE_ENDPOINT=http://localhost:4566

# For AWS environment
export AWS_REGION=ap-northeast-2
# AWS credentials should be configured via AWS CLI or IAM roles
```