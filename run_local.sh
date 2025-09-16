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

# Default environment variables
export AWS_REGION=ap-northeast-2
export INVENTORY_GRPC_ADDRESS=localhost:9090
export JWT_ISSUER_URI=http://localhost:8080/auth/realms/traffic-tacos
export OTLP_ENDPOINT=http://localhost:4318/v1/metrics

# Mode selection (local or aws)
MODE=${MODE:-local}

# Environment configuration
ENVIRONMENT=${ENVIRONMENT:-dev}

# Expected table names based on infrastructure specs
RESERVATION_TABLES=(
    "ticket-reservation-reservations"
    "ticket-reservation-orders"
    "ticket-reservation-idempotency"
    "ticket-reservation-outbox"
)

# Expected EventBridge resources
RESERVATION_EVENT_BUS="ticket-reservation-events"
MAIN_EVENT_BUS="ticket-ticket-events"

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

# Function to check and setup Java version
check_java_version() {
    # Try to source SDKMAN if available
    if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
        source "$HOME/.sdkman/bin/sdkman-init.sh" >/dev/null 2>&1
    fi
    
    if ! command_exists java; then
        print_error "Java is not installed or not in PATH"
        if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
            print_status "SDKMAN detected. You can install Java 17 with:"
            print_status "  source ~/.sdkman/bin/sdkman-init.sh"
            print_status "  sdk install java 17.0.16-zulu"
        fi
        return 1
    fi
    
    local java_version=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' 2>/dev/null || java --version 2>&1 | head -n 1 | awk '{print $2}')
    local major_version=$(echo "$java_version" | cut -d. -f1)
    
    print_status "Detected Java version: $java_version (major: $major_version)"
    
    # Check if Java 17 is available
    if [ "$major_version" != "17" ]; then
        print_warning "Current Java version is $major_version, but project requires Java 17"
        
        # Try SDKMAN first
        if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
            print_status "Checking SDKMAN for Java 17..."
            if command_exists sdk; then
                local java17_available=$(sdk list java 2>/dev/null | grep "17\." | grep "zulu" | head -n 1 | awk '{print $NF}' | tr -d '[:space:]')
                if [ -n "$java17_available" ]; then
                    print_status "Found Java 17 via SDKMAN, switching to it..."
                    sdk use java 17.0.16-zulu >/dev/null 2>&1 || true
                    # Re-check version after switching
                    local new_version=$(java --version 2>&1 | head -n 1 | awk '{print $2}' | cut -d. -f1)
                    if [ "$new_version" = "17" ]; then
                        print_success "Successfully switched to Java 17 via SDKMAN"
                        return 0
                    fi
                fi
            fi
        fi
        
        # Try system Java installation
        if [ -x "/usr/libexec/java_home" ]; then
            local java17_home=$(/usr/libexec/java_home -v 17 2>/dev/null)
            if [ $? -eq 0 ] && [ -n "$java17_home" ]; then
                print_status "Found Java 17 at: $java17_home"
                export JAVA_HOME="$java17_home"
                export PATH="$JAVA_HOME/bin:$PATH"
                print_success "Switched to Java 17"
                return 0
            fi
        fi
        
        print_error "Java 17 is required but not found"
        print_status "Please install Java 17. Options:"
        if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
            print_status "  Via SDKMAN: sdk install java 17.0.16-zulu"
        fi
        print_status "  Via Homebrew: brew install openjdk@17"
        return 1
    fi
    
    print_success "Java 17 is available"
    return 0
}

# Function to validate prerequisites
validate_prerequisites() {
    local missing_deps=()
    
    if [ "$MODE" = "aws" ]; then
        if ! command_exists aws; then
            missing_deps+=("aws-cli")
        fi
    else
        if ! command_exists docker; then
            missing_deps+=("docker")
        fi
    fi
    
    if [ ${#missing_deps[@]} -gt 0 ]; then
        print_error "Missing required dependencies: ${missing_deps[*]}"
        print_status "Please install the missing dependencies and try again."
        return 1
    fi
    
    # Check Java version
    if ! check_java_version; then
        return 1
    fi
    
    return 0
}

# Function to set environment variables based on mode
set_environment() {
    if [ "$MODE" = "aws" ]; then
        print_status "Setting up AWS environment..."
        export SPRING_PROFILES_ACTIVE=dev
        
        # Set DynamoDB table names based on infrastructure specs
        export APP_DYNAMODB_TABLES_RESERVATIONS="ticket-reservation-reservations"
        export APP_DYNAMODB_TABLES_ORDERS="ticket-reservation-orders"
        export APP_DYNAMODB_TABLES_IDEMPOTENCY="ticket-reservation-idempotency"
        export APP_DYNAMODB_TABLES_OUTBOX="ticket-reservation-outbox"
        
        # EventBridge configuration
        export AWS_EVENTBRIDGE_BUS_NAME="ticket-reservation-events"
        
        # AWS endpoints (use actual AWS services)
        unset DYNAMODB_ENDPOINT
        unset EVENTBRIDGE_ENDPOINT
        
        print_success "AWS environment configured"
    else
        print_status "Setting up local environment..."
        export SPRING_PROFILES_ACTIVE=local
        export DYNAMODB_ENDPOINT=http://localhost:8000
        export EVENTBRIDGE_ENDPOINT=http://localhost:4566
        
        # Local table names (for DynamoDB Local)
        export APP_DYNAMODB_TABLES_RESERVATIONS="reservations"
        export APP_DYNAMODB_TABLES_ORDERS="orders"
        export APP_DYNAMODB_TABLES_IDEMPOTENCY="idempotency"
        export APP_DYNAMODB_TABLES_OUTBOX="outbox"
        
        print_success "Local environment configured"
    fi
}

# Function to validate AWS resources
validate_aws_resources() {
    print_status "Validating required AWS resources..."
    
    # Check AWS connectivity
    if ! aws sts get-caller-identity >/dev/null 2>&1; then
        print_error "AWS CLI not configured or no access. Please run 'aws configure' first."
        return 1
    fi
    
    local account_id=$(aws sts get-caller-identity --query Account --output text)
    print_status "Connected to AWS Account: $account_id"
    
    # Check required DynamoDB tables
    local missing_tables=()
    for table in "${RESERVATION_TABLES[@]}"; do
        if ! aws dynamodb describe-table --table-name "$table" --region $AWS_REGION >/dev/null 2>&1; then
            missing_tables+=("$table")
        fi
    done
    
    if [ ${#missing_tables[@]} -gt 0 ]; then
        print_error "Missing required DynamoDB tables:"
        for table in "${missing_tables[@]}"; do
            echo "  - $table"
        done
        print_status "Please deploy the infrastructure or check table names."
        return 1
    fi
    
    print_success "All required DynamoDB tables found"
    
    # Check EventBridge buses
    local missing_buses=()
    if ! aws events describe-event-bus --name "$RESERVATION_EVENT_BUS" --region $AWS_REGION >/dev/null 2>&1; then
        missing_buses+=("$RESERVATION_EVENT_BUS")
    fi
    
    if [ ${#missing_buses[@]} -gt 0 ]; then
        print_warning "Missing EventBridge buses: ${missing_buses[*]}"
        print_status "The application will use the default event bus for now."
    else
        print_success "Required EventBridge buses found"
    fi
    
    return 0
}

# Function to discover AWS resources
discover_aws_resources() {
    print_status "Discovering AWS resources..."
    
    # Check AWS connectivity
    if ! aws sts get-caller-identity >/dev/null 2>&1; then
        print_error "AWS CLI not configured or no access. Please run 'aws configure' first."
        return 1
    fi
    
    local account_id=$(aws sts get-caller-identity --query Account --output text)
    print_status "Connected to AWS Account: $account_id"
    
    # List available DynamoDB tables (filter for reservation tables)
    print_status "Available DynamoDB tables (reservation-related):"
    aws dynamodb list-tables --region $AWS_REGION --query 'TableNames[?contains(@, `reservation`) || contains(@, `ticket`)]' --output table
    
    # List EventBridge buses
    print_status "Available EventBridge buses:"
    aws events list-event-buses --region $AWS_REGION --query 'EventBuses[].Name' --output table
    
    # Check specific reservation tables
    print_status "Checking required reservation tables:"
    for table in "${RESERVATION_TABLES[@]}"; do
        if aws dynamodb describe-table --table-name "$table" --region $AWS_REGION >/dev/null 2>&1; then
            print_success "✓ $table (exists)"
        else
            print_error "✗ $table (missing)"
        fi
    done
    
    # Check EventBridge buses
    print_status "Checking required EventBridge buses:"
    if aws events describe-event-bus --name "$RESERVATION_EVENT_BUS" --region $AWS_REGION >/dev/null 2>&1; then
        print_success "✓ $RESERVATION_EVENT_BUS (exists)"
    else
        print_error "✗ $RESERVATION_EVENT_BUS (missing)"
    fi
    
    return 0
}

# Function to setup Docker services
setup_docker_services() {
    if [ "$MODE" = "aws" ]; then
        print_status "Skipping Docker setup for AWS mode..."
        return 0
    fi
    
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
    if [ "$MODE" = "aws" ]; then
        print_status "Skipping DynamoDB table creation for AWS mode (using existing tables)..."
        print_status "Note: Make sure the following tables exist in AWS:"
        echo "  - reservations"
        echo "  - orders" 
        echo "  - idempotency"
        echo "  - outbox"
        return 0
    fi
    
    print_status "Creating DynamoDB tables for local development..."

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

# Function to show environment info
show_environment_info() {
    print_status "Environment Information:"
    echo "  Mode: $MODE"
    echo "  Environment: $ENVIRONMENT"
    echo "  AWS Region: $AWS_REGION"
    echo "  Spring Profile: ${SPRING_PROFILES_ACTIVE:-not set}"
    
    if [ "$MODE" = "aws" ]; then
        echo "  DynamoDB Tables:"
        echo "    - Reservations: ${APP_DYNAMODB_TABLES_RESERVATIONS:-not set}"
        echo "    - Orders: ${APP_DYNAMODB_TABLES_ORDERS:-not set}"
        echo "    - Idempotency: ${APP_DYNAMODB_TABLES_IDEMPOTENCY:-not set}"
        echo "    - Outbox: ${APP_DYNAMODB_TABLES_OUTBOX:-not set}"
        echo "  EventBridge Bus: ${AWS_EVENTBRIDGE_BUS_NAME:-not set}"
    else
        echo "  Local Services:"
        echo "    - DynamoDB: ${DYNAMODB_ENDPOINT:-not set}"
        echo "    - EventBridge: ${EVENTBRIDGE_ENDPOINT:-not set}"
    fi
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  setup       - Setup Docker services and create DynamoDB tables"
    echo "  build       - Build the application"
    echo "  run         - Run the application"
    echo "  start       - Setup, build and run the application"
    echo "  stop        - Stop Docker services and cleanup"
    echo "  test        - Run tests"
    echo "  aws-info    - Show available AWS resources"
    echo "  aws-validate - Validate required AWS resources exist"
    echo "  env-info    - Show current environment configuration"
    echo "  help        - Show this help message"
    echo ""
    echo "Environment Variables:"
    echo "  MODE        - Set to 'local' (default) or 'aws'"
    echo "  ENVIRONMENT - Environment name (default: 'dev')"
    echo ""
    echo "Examples:"
    echo "  $0 start                    # Complete setup and run (local mode)"
    echo "  MODE=local $0 start         # Explicit local mode with Docker"
    echo "  MODE=aws $0 start           # Use AWS resources"
    echo "  MODE=aws $0 aws-validate    # Check if AWS resources exist"
    echo "  $0 aws-info                 # Discover available AWS resources"
    echo "  $0 env-info                 # Show environment configuration"
    echo "  $0 setup                    # Only setup infrastructure"
    echo "  $0 build                    # Only build application"
}

# Main script logic
print_status "Mode: $MODE (Environment: $ENVIRONMENT)"

# Validate prerequisites first
if ! validate_prerequisites; then
    exit 1
fi

set_environment

case "${1:-start}" in
    "setup")
        if [ "$MODE" = "aws" ]; then
            if ! validate_aws_resources; then
                print_error "AWS resource validation failed. Cannot proceed with setup."
                exit 1
            fi
        fi
        setup_docker_services
        create_dynamodb_tables
        ;;
    "build")
        build_application
        ;;
    "run")
        if [ "$MODE" = "aws" ]; then
            if ! validate_aws_resources; then
                print_error "AWS resource validation failed. Cannot start application."
                exit 1
            fi
        fi
        run_application
        ;;
    "start")
        if [ "$MODE" = "aws" ]; then
            if ! validate_aws_resources; then
                print_error "AWS resource validation failed. Cannot proceed with start."
                exit 1
            fi
        fi
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
    "aws-info")
        discover_aws_resources
        ;;
    "aws-validate")
        if [ "$MODE" != "aws" ]; then
            print_warning "aws-validate command is only relevant in AWS mode"
            print_status "Current mode: $MODE"
            exit 1
        fi
        validate_aws_resources
        ;;
    "env-info")
        show_environment_info
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
