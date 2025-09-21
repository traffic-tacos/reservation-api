#!/bin/bash

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_status "Generating protobuf files..."

# Clean previous generated files
print_status "Cleaning previous generated files..."
rm -rf build/generated/source/proto/

# Generate protobuf files
print_status "Running protobuf generation..."
./gradlew generateProto

# Check if generation was successful
if [ -d "build/generated/source/proto/main" ]; then
    print_success "Protobuf generation completed successfully!"

    print_status "Generated files:"
    find build/generated/source/proto/ -name "*.java" | head -10

    print_status "You can now build the project with: ./gradlew build"
else
    echo -e "${RED}[ERROR]${NC} Protobuf generation failed!"
    exit 1
fi