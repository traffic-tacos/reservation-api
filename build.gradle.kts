import com.google.protobuf.gradle.id

plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.5"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.4"
}

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
}

extra["springGrpcVersion"] = "0.10.0"

dependencies {
	// Spring Boot Starters
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-aop")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webflux")  // WebFlux for high performance
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	// Performance Optimization
	implementation("com.fasterxml.jackson.module:jackson-module-afterburner")  // Jackson Afterburner

	// gRPC
	implementation("io.grpc:grpc-services")
	implementation("org.springframework.grpc:spring-grpc-server-web-spring-boot-starter")

	// AWS SDK v2
	implementation("software.amazon.awssdk:dynamodb:2.25.16")
	implementation("software.amazon.awssdk:eventbridge:2.25.16")
	implementation("software.amazon.awssdk:core:2.25.16")

	// Resilience4j
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
	implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
	implementation("io.github.resilience4j:resilience4j-retry:2.2.0")
	implementation("io.github.resilience4j:resilience4j-timelimiter:2.2.0")
	implementation("io.github.resilience4j:resilience4j-micrometer:2.2.0")

	// Kotlin
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

	// Annotations
	implementation("javax.annotation:javax.annotation-api:1.3.2")

	// Observability
	runtimeOnly("io.micrometer:micrometer-registry-otlp")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")

	// Logging
	runtimeOnly("net.logstash.logback:logstash-logback-encoder:7.4")

	// OpenAPI/Swagger
	implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.6.0")

	// Test Dependencies
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.grpc:spring-grpc-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.testcontainers:testcontainers:1.19.7")
	testImplementation("org.testcontainers:junit-jupiter:1.19.7")
	testImplementation("org.testcontainers:localstack:1.19.7")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.grpc:spring-grpc-dependencies:${property("springGrpcVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

protobuf {
	protoc {
		artifact = "com.google.protobuf:protoc"
	}
	plugins {
		id("grpc") {
			artifact = "io.grpc:protoc-gen-grpc-java"
		}
	}
	generateProtoTasks {
		all().forEach {
			it.plugins {
				id("grpc") {
					option("@generated=omit")
				}
			}
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
