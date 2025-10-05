import com.google.protobuf.gradle.id
import java.nio.file.Files

plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.5"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.4"
}

// ---- 옵션/기본값 ----
val contractsRootProp = providers.gradleProperty("contracts.root").orNull    // 외부에서 -Pcontracts.root=/path
val contractsUseArtifact = providers.gradleProperty("contracts.useArtifact").orNull == "true"
val defaultContractsRoot = "proto-contracts"  // monorepo 구조: repos/proto-contracts/가 build context에 포함될 때
val contractsRoot = contractsRootProp ?: defaultContractsRoot
val contractsDir = file(contractsRoot)

group = "com.traffictacos"
version = "0.0.1-SNAPSHOT"
description = "Ticket Reservation API Service (Spring Boot, Cloud-Native)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
	// GitHub Packages에서 proto-contracts 가져오기 (임시로 로컬 패키지 대신 구현)
	maven {
		url = uri("https://github.com/traffic-tacos/proto-contracts/packages/maven")
	}
}

extra["springGrpcVersion"] = "0.10.0"
extra["awsSdkVersion"] = "2.28.29"
extra["resilience4jVersion"] = "2.2.0"

dependencies {
	// gRPC/Protobuf dependencies
	implementation("io.grpc:grpc-kotlin-stub:1.4.1")
	implementation("io.grpc:grpc-protobuf:1.66.0")
	implementation("com.google.protobuf:protobuf-kotlin:3.25.3")

	// ↓ 아티팩트 모드일 때 사용할 좌표(예시). 내부 레지스트리에 배포해두면 더 Best.
	val contractsVersion = providers.gradleProperty("contracts.version").orElse("1.0.0").get()
	if (contractsUseArtifact || !contractsDir.isDirectory) {
		// 생성된 코드 아티팩트를 쓸 때 (옵션 B)
		// repositories 블록에 내부 레지스트리 추가가 필요할 수 있음
		// repositories { maven { url = uri("https://maven.pkg.github.com/ORG/PACKAGES") } }
		// implementation("com.traffictacos:proto-contracts-kotlin:$contractsVersion")
		logger.warn("Proto-contracts artifact mode enabled but no artifact dependency configured yet")
	}

	// Spring Boot Core
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// Kotlin
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

	// gRPC (devh 사용, Spring Boot 기본 gRPC 제거하여 충돌 방지)
	implementation("io.grpc:grpc-services:1.66.0")
	implementation("net.devh:grpc-server-spring-boot-starter:2.15.0.RELEASE")
	implementation("net.devh:grpc-client-spring-boot-starter:2.15.0.RELEASE")
	implementation("io.grpc:grpc-kotlin-stub:1.4.1")
	implementation("io.grpc:grpc-protobuf:1.66.0")
	implementation("io.grpc:grpc-netty-shaded:1.66.0")
	implementation("io.grpc:grpc-census:1.66.0")
	implementation("io.opencensus:opencensus-api:0.31.1")
	implementation("io.opencensus:opencensus-impl:0.31.1")

	// AWS SDK v2
	implementation("software.amazon.awssdk:dynamodb:${property("awsSdkVersion")}")
	implementation("software.amazon.awssdk:dynamodb-enhanced:${property("awsSdkVersion")}")
	implementation("software.amazon.awssdk:eventbridge:${property("awsSdkVersion")}")
	implementation("software.amazon.awssdk:scheduler:${property("awsSdkVersion")}")
	implementation("software.amazon.awssdk:secretsmanager:${property("awsSdkVersion")}")

	// Resilience4j
	implementation("io.github.resilience4j:resilience4j-spring-boot3:${property("resilience4jVersion")}")
	implementation("io.github.resilience4j:resilience4j-reactor:${property("resilience4jVersion")}")

	// OpenAPI/Swagger
	implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.0")

	// Caching
	implementation("com.github.ben-manes.caffeine:caffeine")

	// Observability
	runtimeOnly("io.micrometer:micrometer-registry-otlp")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
	implementation(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.20.1"))

	// Test Dependencies
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.grpc:spring-grpc-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.testcontainers:testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:localstack")
	testImplementation("com.amazonaws:DynamoDBLocal:2.5.2")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
	testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.grpc:spring-grpc-dependencies:${property("springGrpcVersion")}")
		mavenBom("software.amazon.awssdk:bom:${property("awsSdkVersion")}")
		mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

protobuf {
	protoc { artifact = "com.google.protobuf:protoc:3.25.3" }
	plugins {
		id("grpc")   { artifact = "io.grpc:protoc-gen-grpc-java:1.66.0" }
		id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar" }
	}
	generateProtoTasks {
		all().configureEach {
			plugins {
				id("grpc") {
					option("@generated=omit")
				}
				id("grpckt") {
					option("@generated=omit")
				}
			}
		}
	}
}

// ---- 소스 경로 전환 ----
sourceSets {
	main {
		proto {
			srcDirs.clear()
			if (!contractsUseArtifact && contractsDir.isDirectory) {
				// 옵션 A: 로컬에 proto-contracts가 있을 때 → 루트를 include root로
				// (proto 파일들이 import "proto/..." 를 쓰므로 루트가 proto-contracts/ 여야 함)
				srcDir(contractsDir.path)           // ex) proto-contracts/
				logger.quiet("Using local proto-contracts at: ${contractsDir.absolutePath}")
			} else {
				// 옵션 B: 아티팩트 사용 → 로컬 proto 없음
				// (generateProto는 비활성화되지 않아도 상관 없지만, 성능 위해 굳이 꺼도 됨)
				logger.quiet("Proto-contracts not found locally, expecting artifact dependency")
			}
		}
	}
}

// 아티팩트 모드면 generateProto가 굳이 필요 없지만, 켜져 있어도 영향 없음.
// 필요하면 아래처럼 비활성화 가능:
tasks.named("generateProto") {
	onlyIf { !contractsUseArtifact && contractsDir.isDirectory }
}

tasks.withType<Test> {
	useJUnitPlatform()
}
