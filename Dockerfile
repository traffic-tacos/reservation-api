# syntax=docker/dockerfile:1.7
############################
# 1) CODEGEN & BUILD
############################
FROM gradle:8.7.0-jdk17 AS builder

# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends bash unzip git && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# ---- 빌드 인자 ----
ARG CONTRACTS_MODE=auto         	# auto | local | git | artifact
ARG CONTRACTS_REF=main          	# git 모드일 때 사용할 브랜치/태그/SHA
ARG CONTRACTS_URL=https://github.com/traffic-tacos/proto-contracts.git

# ---- Gradle 스켈레톤/캐시 ----
COPY /gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --version
RUN ./gradlew --no-daemon dependencies || true

# ---- 소스 복사 ----
COPY src/ src/

# ---- proto-contracts 주입 (named build-context) ----
# ✅ 로컬 contracts를 "있으면" 복사하고, 없으면 그냥 빈 컨텍스트로 넘어가게 만든다
#    (빌드 시 --build-context contracts=./proto-contracts 를 주면 이 COPY가 채워짐)
COPY --from=contracts / /workspace/proto-contracts-local/

# ---- 모드별 빌드 실행 ----
RUN if [ "$CONTRACTS_MODE" = "local" ] || { [ "$CONTRACTS_MODE" = "auto" ] && [ -d "proto-contracts-local/proto" ]; }; then \
      echo ">>> Using LOCAL proto-contracts"; \
      ./gradlew --no-daemon clean generateProto -Pcontracts.root=/workspace/proto-contracts-local && \
      ./gradlew --no-daemon build -x test -Pcontracts.root=/workspace/proto-contracts-local; \
    elif [ "$CONTRACTS_MODE" = "git" ] || [ "$CONTRACTS_MODE" = "auto" ]; then \
      echo ">>> Using GIT proto-contracts ($CONTRACTS_REF)"; \
      git clone --depth 1 -b "$CONTRACTS_REF" "$CONTRACTS_URL" /workspace/proto-contracts && \
      ./gradlew --no-daemon clean generateProto -Pcontracts.root=/workspace/proto-contracts && \
      ./gradlew --no-daemon build -x test -Pcontracts.root=/workspace/proto-contracts; \
    elif [ "$CONTRACTS_MODE" = "artifact" ]; then \
      echo ">>> Using ARTIFACT mode"; \
      ./gradlew --no-daemon clean build -x test -Pcontracts.useArtifact=true; \
    else \
      echo "Unknown CONTRACTS_MODE=$CONTRACTS_MODE" && exit 1; \
    fi

############################
# 2) RUNTIME (타깃 아키텍처)
############################
FROM amazoncorretto:17-alpine AS runtime

# Create non-root user
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

# Copy jar from builder
COPY --from=builder /workspace/build/libs/*.jar app.jar

# Change ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Spring Boot ports
EXPOSE 8010 8011

# Runtime environment variables
ENV JAVA_OPTS=""
ENV SPRING_PROFILES_ACTIVE="docker"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
