# Multi-stage build for Traffic Tacos Reservation API
# Stage 1: Build stage
FROM gradle:8.6-jdk17 AS builder

# Install security updates
RUN apt-get update && apt-get upgrade -y && apt-get clean && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Create gradle cache directory for better performance
RUN mkdir -p /home/gradle/.gradle && chmod 777 /home/gradle/.gradle

# Copy Gradle files for dependency caching
COPY build.gradle.kts settings.gradle.kts gradle/ gradle/
COPY gradlew gradlew.bat ./

# Make gradlew executable
RUN chmod +x gradlew

# Download dependencies (cached if gradle files don't change)
RUN ./gradlew dependencies --no-daemon --parallel

# Copy source code
COPY src/ src/

# Build the application with parallel processing
RUN ./gradlew clean build --no-daemon -x test --parallel --build-cache

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-alpine AS runtime

# Install necessary packages
RUN apk add --no-cache \
    curl \
    && rm -rf /var/cache/apk/*

# Create a non-root user
RUN addgroup -g 1001 -S appuser && \
    adduser -u 1001 -S appuser -G appuser

# Set working directory
WORKDIR /app

# Copy the JAR file from builder stage
COPY --from=builder /app/build/libs/reservation-api-*.jar app.jar

# Create log directory
RUN mkdir -p /app/logs && \
    chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Set JVM options for performance
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:+UseContainerSupport \
               -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=16m \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.profiles.active=prod"

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Set the entrypoint
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
