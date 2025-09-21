#!/bin/bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

# Function to check if a port is in use
port_in_use() {
    lsof -Pi :$1 -sTCP:LISTEN -t >/dev/null
}

# Function to wait for service to be ready
wait_for_service() {
    local host=$1
    local port=$2
    local service_name=$3
    local max_attempts=30
    local attempt=1

    print_status "Waiting for $service_name to be ready..."

    while [ $attempt -le $max_attempts ]; do
        if nc -z $host $port 2>/dev/null; then
            print_success "$service_name is ready!"
            return 0
        fi

        echo -n "."
        sleep 2
        attempt=$((attempt + 1))
    done

    print_error "$service_name failed to start within $((max_attempts * 2)) seconds"
    return 1
}

# Function to setup infrastructure
setup_infrastructure() {
    print_status "Setting up local infrastructure..."

    # Check if Docker is running
    if ! docker info >/dev/null 2>&1; then
        print_error "Docker is not running. Please start Docker and try again."
        exit 1
    fi

    # Stop any existing containers
    print_status "Stopping existing containers..."
    docker-compose -f docker-compose.local.yml down --remove-orphans 2>/dev/null || true

    # Start infrastructure services
    print_status "Starting infrastructure services..."
    docker-compose -f docker-compose.local.yml up -d

    # Wait for services to be ready
    wait_for_service localhost 8000 "DynamoDB Local"
    wait_for_service localhost 4566 "LocalStack"
    wait_for_service localhost 9090 "Prometheus"
    wait_for_service localhost 3000 "Grafana"

    print_success "Infrastructure setup complete!"
    print_status "Services available at:"
    echo "  - DynamoDB Local: http://localhost:8000"
    echo "  - LocalStack: http://localhost:4566"
    echo "  - Prometheus: http://localhost:9090"
    echo "  - Grafana: http://localhost:3000 (admin/admin)"
}

# Function to create DynamoDB tables
create_tables() {
    print_status "Creating DynamoDB tables..."

    # Wait a bit more for DynamoDB to be fully ready
    sleep 5

    # Create tables
    aws dynamodb create-table \
        --table-name reservations \
        --attribute-definitions \
            AttributeName=reservationId,AttributeType=S \
        --key-schema \
            AttributeName=reservationId,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST \
        --endpoint-url http://localhost:8000 \
        --region ap-northeast-2 \
        --no-cli-pager 2>/dev/null || print_warning "reservations table may already exist"

    aws dynamodb create-table \
        --table-name orders \
        --attribute-definitions \
            AttributeName=orderId,AttributeType=S \
        --key-schema \
            AttributeName=orderId,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST \
        --endpoint-url http://localhost:8000 \
        --region ap-northeast-2 \
        --no-cli-pager 2>/dev/null || print_warning "orders table may already exist"

    aws dynamodb create-table \
        --table-name idempotency \
        --attribute-definitions \
            AttributeName=idempotencyKey,AttributeType=S \
        --key-schema \
            AttributeName=idempotencyKey,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST \
        --time-to-live-specification \
            AttributeName=ttl,Enabled=true \
        --endpoint-url http://localhost:8000 \
        --region ap-northeast-2 \
        --no-cli-pager 2>/dev/null || print_warning "idempotency table may already exist"

    aws dynamodb create-table \
        --table-name outbox \
        --attribute-definitions \
            AttributeName=outboxId,AttributeType=S \
        --key-schema \
            AttributeName=outboxId,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST \
        --endpoint-url http://localhost:8000 \
        --region ap-northeast-2 \
        --no-cli-pager 2>/dev/null || print_warning "outbox table may already exist"

    print_success "DynamoDB tables created!"
}

# Function to build the application
build_app() {
    print_status "Building application..."

    # Generate proto files
    ./gradlew generateProto

    # Build the application
    ./gradlew clean build -x test

    print_success "Application built successfully!"
}

# Function to run the application
run_app() {
    print_status "Starting reservation-api..."

    # Check if port 8001 is available
    if port_in_use 8001; then
        print_error "Port 8001 is already in use. Please stop the service using this port."
        exit 1
    fi

    # Set environment variables for local development
    export SPRING_PROFILES_ACTIVE=local
    export AWS_REGION=ap-northeast-2
    export AWS_PROFILE=tacos
    export AWS_DYNAMODB_ENDPOINT=http://localhost:8000
    export AWS_SCHEDULER_ENDPOINT=http://localhost:4566
    export GRPC_CLIENT_INVENTORY_SERVICE_ADDRESS=static://localhost:8002
    export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://dev-auth.traffictacos.com

    print_status "Environment configured for local development"
    print_status "Starting application on port 8001..."

    # Run the application
    ./gradlew bootRun
}

# Function to stop services
stop_services() {
    print_status "Stopping all services..."

    # Stop Docker services
    docker-compose -f docker-compose.local.yml down

    # Kill any Java processes on port 8001
    if port_in_use 8001; then
        lsof -ti:8001 | xargs kill -9 2>/dev/null || true
    fi

    print_success "All services stopped!"
}

# Function to show logs
show_logs() {
    print_status "Showing infrastructure logs..."
    docker-compose -f docker-compose.local.yml logs -f
}

# Function to show help
show_help() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  setup     - Setup infrastructure services (DynamoDB, LocalStack, etc.)"
    echo "  tables    - Create DynamoDB tables"
    echo "  build     - Build the application"
    echo "  run       - Run the application"
    echo "  start     - Setup + Tables + Build + Run (full startup)"
    echo "  stop      - Stop all services"
    echo "  logs      - Show infrastructure logs"
    echo "  help      - Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 start   # Full startup (recommended)"
    echo "  $0 setup   # Just setup infrastructure"
    echo "  $0 run     # Just run the app (assumes infrastructure is ready)"
    echo ""
}

# Function to check prerequisites
check_prerequisites() {
    print_status "Checking prerequisites..."

    # Check required commands
    local missing_commands=()

    if ! command_exists docker; then
        missing_commands+=("docker")
    fi

    if ! command_exists docker-compose; then
        missing_commands+=("docker-compose")
    fi

    if ! command_exists aws; then
        missing_commands+=("aws")
    fi

    if ! command_exists java; then
        missing_commands+=("java")
    fi

    if [ ${#missing_commands[@]} -ne 0 ]; then
        print_error "Missing required commands: ${missing_commands[*]}"
        echo "Please install the missing commands and try again."
        exit 1
    fi

    # Check Java version
    java_version=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | awk -F '.' '{print $1}')
    if [ "$java_version" -lt 17 ]; then
        print_error "Java 17 or higher is required. Current version: $java_version"
        exit 1
    fi

    print_success "Prerequisites check passed!"
}

# Main script logic
case "${1:-help}" in
    setup)
        check_prerequisites
        setup_infrastructure
        ;;
    tables)
        create_tables
        ;;
    build)
        build_app
        ;;
    run)
        run_app
        ;;
    start)
        check_prerequisites
        setup_infrastructure
        create_tables
        build_app
        run_app
        ;;
    stop)
        stop_services
        ;;
    logs)
        show_logs
        ;;
    help)
        show_help
        ;;
    *)
        print_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac