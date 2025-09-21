FROM gradle:8.14.3-jdk17-alpine AS builder

WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src/ src/

RUN ./gradlew clean build -x test --no-daemon

FROM amazoncorretto:17-alpine3.20-jdk

WORKDIR /app

RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

COPY --from=builder /app/build/libs/*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8001/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]