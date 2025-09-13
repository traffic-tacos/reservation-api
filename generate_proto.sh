#!/bin/bash

# Traffic Tacos Reservation API - Proto Generation Script
# This script generates Kotlin gRPC stubs from proto files

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PROTO_DIR="src/main/proto"
GENERATED_DIR="src/main/kotlin"
PROTO_FILE="inventory.proto"

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

# Function to check proto file
check_proto_file() {
    if [ ! -f "$PROTO_DIR/$PROTO_FILE" ]; then
        print_error "Proto file not found: $PROTO_DIR/$PROTO_FILE"
        exit 1
    fi

    print_status "Found proto file: $PROTO_DIR/$PROTO_FILE"
}

# Function to install protoc plugins if needed
install_protoc_plugins() {
    print_status "Checking protoc plugins..."

    # Check if protoc-gen-grpc-java is available
    if ! command_exists protoc-gen-grpc-java; then
        print_warning "protoc-gen-grpc-java not found. Installing..."

        # Try to install via apt (Ubuntu/Debian)
        if command_exists apt; then
            sudo apt update
            sudo apt install -y protobuf-compiler-grpc
        # Try to install via brew (macOS)
        elif command_exists brew; then
            brew install protobuf
            brew install protoc-gen-grpc-java
        else
            print_error "Please install protoc-gen-grpc-java manually"
            print_error "Visit: https://github.com/grpc/grpc-java"
            exit 1
        fi
    fi

    print_success "protoc-gen-grpc-java is available"
}

# Function to generate proto files
generate_proto() {
    print_status "Generating Kotlin gRPC stubs from proto files..."

    # Create output directory if it doesn't exist
    mkdir -p "$GENERATED_DIR"

    # Run protoc with gradle plugin (this is handled by the gradle protobuf plugin)
    # The actual generation is done by Gradle, but we can trigger it here

    if [ -f "gradlew" ]; then
        print_status "Running Gradle protobuf compilation..."
        ./gradlew clean generateProto

        if [ $? -eq 0 ]; then
            print_success "Proto generation completed successfully"
        else
            print_error "Proto generation failed"
            exit 1
        fi
    else
        print_error "Gradle wrapper not found. Please run this script from the project root."
        exit 1
    fi
}

# Function to verify generated files
verify_generated_files() {
    print_status "Verifying generated files..."

    # Check if the generated files exist
    GENERATED_PACKAGE_DIR="$GENERATED_DIR/com/traffictacos/reservation/grpc/inventory"

    if [ -d "$GENERATED_PACKAGE_DIR" ]; then
        print_success "Generated package directory found: $GENERATED_PACKAGE_DIR"

        # List generated files
        GENERATED_FILES=$(find "$GENERATED_PACKAGE_DIR" -name "*.java" -o -name "*.kt" 2>/dev/null)
        if [ -n "$GENERATED_FILES" ]; then
            print_status "Generated files:"
            echo "$GENERATED_FILES"
        else
            print_warning "No generated files found in package directory"
        fi
    else
        print_warning "Generated package directory not found: $GENERATED_PACKAGE_DIR"
        print_status "This might be normal if the package structure is different"
    fi
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -h, --help     Show this help message"
    echo "  -c, --check    Only check if proto file exists"
    echo "  -v, --verify   Only verify generated files"
    echo ""
    echo "Description:"
    echo "  This script generates Kotlin gRPC stubs from proto files using Gradle."
    echo "  It requires the protobuf-gradle-plugin to be configured in build.gradle.kts"
    echo ""
    echo "Examples:"
    echo "  $0              # Generate proto files"
    echo "  $0 --check      # Only check proto file"
    echo "  $0 --verify     # Only verify generated files"
}

# Main script logic
case "${1:-}" in
    "-h"|"--help")
        show_usage
        exit 0
        ;;
    "-c"|"--check")
        check_proto_file
        exit 0
        ;;
    "-v"|"--verify")
        verify_generated_files
        exit 0
        ;;
    "")
        # Default behavior: check, install dependencies, generate
        check_proto_file
        install_protoc_plugins
        generate_proto
        verify_generated_files
        ;;
    *)
        print_error "Unknown option: $1"
        show_usage
        exit 1
        ;;
esac

print_success "Proto generation process completed!"
