# Traffic Tacos Reservation API

🌮 High-performance reservation service for Traffic Tacos ticket system, designed to handle **30k RPS traffic** with 60-second hold periods and zero oversell guarantee.

## ✅ Implementation Status

**Core Features**: ✅ Complete
- ✅ Reservation creation with inventory validation
- ✅ 60-second automatic expiry via AWS EventBridge Scheduler
- ✅ Idempotent operations with TTL-based deduplication
- ✅ Reservation confirmation and cancellation
- ✅ Event-driven architecture with outbox pattern
- ✅ gRPC communication with inventory service
- ✅ JWT authentication and security
- ✅ Comprehensive error handling and validation
- ✅ Health checks and monitoring endpoints

**Infrastructure**: ✅ Complete
- ✅ AWS DynamoDB integration with Enhanced Client
- ✅ AWS EventBridge and EventBridge Scheduler
- ✅ OpenTelemetry observability stack
- ✅ Prometheus metrics and Grafana dashboards
- ✅ Docker containerization with multi-stage builds
- ✅ Local development environment with docker-compose

**API & Documentation**: ✅ Complete
- ✅ RESTful API with comprehensive validation
- ✅ OpenAPI 3.0 specification and Swagger UI
- ✅ Protocol Buffer definitions for gRPC
- ✅ Production-ready configuration management

## 🚀 Features

- **High Performance**: 30k RPS capacity, P95 < 120ms latency target
- **60-Second Hold**: Automatic reservation expiry via EventBridge Scheduler
- **Idempotent Operations**: Request deduplication with 5-minute TTL
- **gRPC Integration**: High-performance communication with inventory service
- **Event-Driven Architecture**: Outbox pattern with EventBridge/SQS
- **JWT Authentication**: OAuth2 resource server with Spring Security
- **Observability**: OpenTelemetry, Prometheus metrics, distributed tracing
- **Cloud-Native**: AWS DynamoDB, EventBridge, Scheduler integration

## 🏗️ Architecture

```
┌─────────────────┐    gRPC    ┌─────────────────┐
│  Gateway API    │ ---------> │ Reservation API │
│  (HTTP/REST)    │            │  (This Service) │
└─────────────────┘            └─────────────────┘
                                       │
                                       │ gRPC
                                       ▼
                               ┌─────────────────┐
                               │  Inventory API  │
                               │   (Go/gRPC)     │
                               └─────────────────┘
                                       │
                                       │ Events
                                       ▼
                               ┌─────────────────┐
                               │ EventBridge/SQS │
                               │   Background    │
                               │    Workers      │
                               └─────────────────┘
```

## 🛠️ Technology Stack

- **Framework**: Spring Boot 3.5.5 WebFlux (Reactive)
- **Language**: Kotlin 1.9.25
- **Database**: AWS DynamoDB (with Enhanced Client)
- **Communication**: gRPC, REST API
- **Authentication**: JWT OAuth2 Resource Server
- **Events**: AWS EventBridge, EventBridge Scheduler
- **Observability**: OpenTelemetry, Prometheus, Micrometer
- **Build**: Gradle with Kotlin DSL
- **Container**: Docker with multi-stage builds

## 📋 Prerequisites

- **Java 17+**
- **Docker & Docker Compose**
- **AWS CLI** (configured with `tacos` profile)
- **Gradle 8.14.3+** (included via wrapper)

## 🚀 Quick Start

### 1. Full Local Development Setup

```bash
# Start all infrastructure + build + run
./run_local.sh start
```

This will:
- Start DynamoDB Local, LocalStack, Prometheus, Grafana
- Create DynamoDB tables
- Build the application
- Run the service on port 8001

### 2. Individual Commands

```bash
# Setup infrastructure only
./run_local.sh setup

# Create DynamoDB tables
./run_local.sh tables

# Build application
./run_local.sh build

# Run application (assumes infrastructure is ready)
./run_local.sh run

# Stop all services
./run_local.sh stop

# View infrastructure logs
./run_local.sh logs
```

### 3. Generate Proto Files

```bash
./generate_proto.sh
```

## 🌐 API Endpoints

### Core Reservation APIs

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v1/reservations` | Create reservation (requires `Idempotency-Key`) |
| `GET` | `/v1/reservations/{id}` | Get reservation details |
| `POST` | `/v1/reservations/confirm` | Confirm reservation after payment |
| `POST` | `/v1/reservations/cancel` | Cancel reservation |

### Health & Monitoring

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/health` | Basic health check |
| `GET` | `/info` | Service information |
| `GET` | `/actuator/health` | Detailed health status |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

### API Documentation

- **Swagger UI**: http://localhost:8001/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8001/v3/api-docs

## 🔧 Configuration

### Environment Variables

```bash
# AWS Configuration
AWS_REGION=ap-northeast-2
AWS_PROFILE=tacos
AWS_DYNAMODB_ENDPOINT=http://localhost:8000  # For local development

# Service Configuration
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=8001

# gRPC Client
GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS=static://localhost:8002

# Security
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://your-auth-server.com
```

### DynamoDB Tables

| Table | Purpose | Key |
|-------|---------|-----|
| `reservations` | Main reservation data | `reservationId` (String) |
| `orders` | Confirmed orders | `orderId` (String) |
| `idempotency` | Request deduplication | `idempotencyKey` (String, TTL) |
| `outbox` | Event publishing | `outboxId` (String) |

## 🧪 Testing

### Run Tests

```bash
# Unit tests
./gradlew test

# Integration tests (requires Docker)
./gradlew integrationTest

# All tests
./gradlew check
```

### Test Coverage

```bash
./gradlew jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

## 📊 Monitoring & Observability

### Local Development URLs

- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)
- **OTEL Collector**: http://localhost:4317 (gRPC), http://localhost:4318 (HTTP)

### Key Metrics

- `http_server_requests_*`: HTTP request metrics
- `grpc_client_*`: gRPC client metrics
- `reservation_status_total`: Business metrics by status
- `jvm_*`: JVM performance metrics

### Distributed Tracing

All requests include trace correlation:
```
X-Trace-Id: abc123def456
```

## 🔄 Business Flow

### 1. Create Reservation
```
1. Validate JWT token
2. Check idempotency key
3. Call inventory service (gRPC) for availability
4. Create reservation with 60s hold
5. Schedule expiry via EventBridge Scheduler
6. Publish creation event to outbox
```

### 2. Confirm Reservation
```
1. Validate reservation status & expiry
2. Call inventory service to commit seats
3. Create order record
4. Update reservation to CONFIRMED
5. Publish confirmation event
```

### 3. Automatic Expiry
```
1. EventBridge Scheduler triggers after 60s
2. Background worker processes expiry event
3. Calls inventory service to release hold
4. Updates reservation to EXPIRED
```

## 🏗️ Development

### Project Structure

```
src/main/kotlin/com/traffictacos/reservation/
├── config/          # Spring configuration
├── controller/      # REST API controllers
├── domain/          # Domain entities (DynamoDB)
├── dto/             # Request/Response DTOs
├── grpc/            # gRPC clients
├── repository/      # Data access layer
├── security/        # Security configuration
└── service/         # Business logic

src/main/proto/      # Protocol Buffer definitions
src/main/resources/
├── application.properties
└── openapi/         # OpenAPI specification
```

### Code Style

- **Kotlin**: Official Kotlin style guide
- **Spring**: Reactive patterns with WebFlux
- **Error Handling**: Custom exceptions with error codes
- **Logging**: Structured JSON with trace correlation

### Adding New Features

1. Update proto files if needed: `./generate_proto.sh`
2. Add domain models and DTOs
3. Implement repository layer
4. Add business logic to services
5. Create REST controllers
6. Write comprehensive tests
7. Update API documentation

## 🐳 Docker

### Build Image

```bash
docker build -t reservation-api:latest .
```

### Run Container

```bash
docker run -p 8001:8001 \
  -e SPRING_PROFILES_ACTIVE=local \
  -e AWS_REGION=ap-northeast-2 \
  reservation-api:latest
```

## 🚀 Production Deployment

### Environment Setup

1. **AWS Resources**: Ensure DynamoDB tables, EventBridge bus, and IAM roles exist
2. **Secrets**: Configure AWS Secrets Manager for sensitive values
3. **Monitoring**: Set up CloudWatch dashboards and alarms
4. **Load Testing**: Validate 30k RPS capacity

### Performance Tuning

- **JVM**: `-Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100`
- **Netty**: Tune connection pools and buffer sizes
- **DynamoDB**: Use appropriate read/write capacity or on-demand billing
- **gRPC**: Configure connection pooling and keep-alive settings

## 🤝 Contributing

1. Fork the repository
2. Create feature branch: `git checkout -b feature/amazing-feature`
3. Make changes and add tests
4. Ensure all tests pass: `./gradlew check`
5. Commit with conventional commits: `feat: add amazing feature`
6. Push and create Pull Request

## 📄 License

This project is licensed under the MIT License.

## 🆘 Support

- **Issues**: Create GitHub issues for bugs/features
- **Docs**: Check `/docs` directory for detailed guides
- **Monitoring**: Use Grafana dashboards for troubleshooting