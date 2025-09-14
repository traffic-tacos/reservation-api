#!/bin/bash

# Traffic Tacos Reservation API - Local Development Script
# This script sets up and runs the reservation service with all dependencies

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PROJECT_NAME="reservation-api"
DOCKER_COMPOSE_FILE="docker-compose.local.yml"
JAR_FILE="build/libs/${PROJECT_NAME}-0.0.1-SNAPSHOT.jar"

# Environment variables
export SPRING_PROFILES_ACTIVE=local
export AWS_REGION=ap-northeast-2
export DYNAMODB_ENDPOINT=http://localhost:8000
export EVENTBRIDGE_ENDPOINT=http://localhost:4566
export INVENTORY_GRPC_ADDRESS=localhost:9090
export JWT_ISSUER_URI=http://localhost:8080/auth/realms/traffic-tacos
export OTLP_ENDPOINT=http://localhost:4318/v1/metrics

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to check if a port is available
check_port() {
    if lsof -Pi :$1 -sTCP:LISTEN -t >/dev/null; then
        return 1
    else
        return 0
    fi
}

# Function to wait for service to be ready
wait_for_service() {
    local service=$1
    local port=$2
    local max_attempts=30
    local attempt=1

    print_status "Waiting for $service to be ready on port $port..."

    while [ $attempt -le $max_attempts ]; do
        if nc -z localhost $port 2>/dev/null; then
            print_success "$service is ready!"
            return 0
        fi

        echo -n "."
        sleep 2
        ((attempt++))
    done

    print_error "$service failed to start within expected time"
    return 1
}

# Function to setup Docker services
setup_docker_services() {
    print_status "Setting up Docker services..."

    # Check if Docker is running
    if ! docker info >/dev/null 2>&1; then
        print_error "Docker is not running. Please start Docker first."
        exit 1
    fi

    # Create docker-compose file if it doesn't exist
    if [ ! -f "$DOCKER_COMPOSE_FILE" ]; then
        print_status "Creating docker-compose.local.yml..."
        cat > $DOCKER_COMPOSE_FILE << EOF
version: '3.8'
services:
  dynamodb-local:
    image: amazon/dynamodb-local:latest
    container_name: dynamodb-local
    ports:
      - "8000:8000"
    volumes:
      - ./dynamodb-data:/home/dynamodblocal/data
    command: "-jar DynamoDBLocal.jar -sharedDb -dbPath /home/dynamodblocal/data"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/shell/"]
      interval: 30s
      timeout: 10s
      retries: 5

  localstack:
    image: localstack/localstack:3.0
    container_name: localstack
    ports:
      - "4566:4566"
    environment:
      - SERVICES=lambda,dynamodb,events,iam
      - DEBUG=1
      - DATA_DIR=/tmp/localstack/data
    volumes:
      - "./localstack-init:/etc/localstack/init/ready.d"
      - "./localstack-data:/tmp/localstack"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4566/_localstack/health"]
      interval: 30s
      timeout: 10s
      retries: 5

  otel-collector:
    image: otel/opentelemetry-collector:latest
    container_name: otel-collector
    ports:
      - "4317:4317"   # gRPC receiver
      - "4318:4318"   # HTTP receiver
      - "55679:55679" # zpages
    volumes:
      - ./otel-collector-config.yml:/etc/otelcol/config.yaml
    command: ["--config", "/etc/otelcol/config.yaml"]
EOF
    fi

    # Start Docker services
    print_status "Starting Docker services..."
    docker-compose -f $DOCKER_COMPOSE_FILE up -d

    # Wait for services to be ready
    wait_for_service "DynamoDB Local" 8000
    wait_for_service "LocalStack" 4566
}

# Function to create DynamoDB tables
create_dynamodb_tables() {
    print_status "Creating DynamoDB tables..."

    # Wait a bit for DynamoDB to be fully ready
    sleep 5

    # Create reservations table
    aws dynamodb create-table \
        --table-name reservations \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST \
        --endpoint-url http://localhost:8000 \
        --region us-east-1 || print_warning "Reservations table might already exist"

    # Create orders table
    aws dynamodb create-table \
        --table-name orders \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST \
        --endpoint-url http://localhost:8000 \
        --region us-east-1 || print_warning "Orders table might already exist"

    # Create idempotency table
    aws dynamodb create-table \
        --table-name idempotency \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST \
        --endpoint-url http://localhost:8000 \
        --region us-east-1 || print_warning "Idempotency table might already exist"

    # Create outbox table
    aws dynamodb create-table \
        --table-name outbox \
        --attribute-definitions \
            AttributeName=pk,AttributeType=S \
            AttributeName=sk,AttributeType=S \
            AttributeName=status,AttributeType=S \
            AttributeName=created_at,AttributeType=S \
        --key-schema \
            AttributeName=pk,KeyType=HASH \
            AttributeName=sk,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST \
        --global-secondary-indexes \
            '[
                {
                    "IndexName": "status-created_at-index",
                    "KeySchema": [
                        {"AttributeName": "status", "KeyType": "HASH"},
                        {"AttributeName": "created_at", "KeyType": "RANGE"}
                    ],
                    "Projection": {"ProjectionType": "ALL"}
                }
            ]' \
        --endpoint-url http://localhost:8000 \
        --region us-east-1 || print_warning "Outbox table might already exist"

    print_success "DynamoDB tables created successfully"
}

# Function to build the application
build_application() {
    print_status "Building the application..."

    if [ ! -f "gradlew" ]; then
        print_error "Gradle wrapper not found. Please run this script from the project root."
        exit 1
    fi

    ./gradlew clean build -x test

    if [ ! -f "$JAR_FILE" ]; then
        print_error "Failed to build the application. JAR file not found: $JAR_FILE"
        exit 1
    fi

    print_success "Application built successfully"
}

# Function to run the application
run_application() {
    print_status "Starting the application..."

    # Set JVM options for better performance
    JVM_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseContainerSupport"

    # Run the application
    java $JVM_OPTS -jar $JAR_FILE \
        --spring.profiles.active=local \
        --logging.level.com.traffictacos.reservation=DEBUG
}

# Function to cleanup
cleanup() {
    print_status "Cleaning up..."

    # Stop Docker services
    if [ -f "$DOCKER_COMPOSE_FILE" ]; then
        docker-compose -f $DOCKER_COMPOSE_FILE down -v
    fi

    print_success "Cleanup completed"
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  setup     - Setup Docker services and create DynamoDB tables"
    echo "  build     - Build the application"
    echo "  run       - Run the application"
    echo "  start     - Setup, build and run the application"
    echo "  stop      - Stop Docker services and cleanup"
    echo "  test      - Run tests"
    echo "  help      - Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 start    # Complete setup and run"
    echo "  $0 setup    # Only setup infrastructure"
    echo "  $0 build    # Only build application"
}

# Main script logic
case "${1:-start}" in
    "setup")
        setup_docker_services
        create_dynamodb_tables
        ;;
    "build")
        build_application
        ;;
    "run")
        run_application
        ;;
    "start")
        setup_docker_services
        create_dynamodb_tables
        build_application
        run_application
        ;;
    "stop")
        cleanup
        ;;
    "test")
        print_status "Running tests..."
        ./gradlew test
        ;;
    "help"|"-h"|"--help")
        show_usage
        ;;
    *)
        print_error "Unknown command: $1"
        show_usage
        exit 1
        ;;
esac
