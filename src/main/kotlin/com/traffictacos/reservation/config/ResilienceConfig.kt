package com.traffictacos.reservation.config

import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class ResilienceConfig {

    @Bean
    fun circuitBreakerRegistry(meterRegistry: MeterRegistry): CircuitBreakerRegistry {
        // Inventory service circuit breaker - stricter
        val inventoryConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(40.0f) // Open at 40% failure rate
            .slowCallRateThreshold(60.0f) // Consider slow calls as failures
            .slowCallDurationThreshold(Duration.ofMillis(300)) // 300ms is slow for inventory
            .waitDurationInOpenState(Duration.ofSeconds(30)) // Wait 30s before retry
            .permittedNumberOfCallsInHalfOpenState(5)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .recordExceptions(
                java.net.ConnectException::class.java,
                java.util.concurrent.TimeoutException::class.java,
                io.grpc.StatusRuntimeException::class.java
            )
            .build()

        // DynamoDB circuit breaker - more lenient
        val dynamoConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(60.0f) // Open at 60% failure rate
            .slowCallRateThreshold(70.0f)
            .slowCallDurationThreshold(Duration.ofSeconds(2)) // 2s is slow for DynamoDB
            .waitDurationInOpenState(Duration.ofSeconds(15)) // Wait 15s before retry
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowSize(15)
            .minimumNumberOfCalls(5)
            .recordExceptions(
                software.amazon.awssdk.core.exception.SdkException::class.java,
                java.util.concurrent.TimeoutException::class.java
            )
            .build()

        // EventBridge circuit breaker - most lenient (eventual consistency is OK)
        val eventBridgeConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(80.0f) // Open at 80% failure rate
            .waitDurationInOpenState(Duration.ofMinutes(2)) // Wait 2min before retry
            .permittedNumberOfCallsInHalfOpenState(2)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(3)
            .build()

        val registry = CircuitBreakerRegistry.ofDefaults()
        registry.circuitBreaker("inventory", inventoryConfig)
        registry.circuitBreaker("dynamodb", dynamoConfig)
        registry.circuitBreaker("eventbridge", eventBridgeConfig)

        // Register metrics
        registry.allCircuitBreakers.forEach { circuitBreaker ->
            circuitBreaker.metrics.let { metrics ->
                meterRegistry.gauge("resilience.circuitbreaker.state", 
                    io.micrometer.core.instrument.Tags.of("name", circuitBreaker.name)) {
                    when (circuitBreaker.state) {
                        CircuitBreaker.State.OPEN -> 1.0
                        CircuitBreaker.State.HALF_OPEN -> 0.5
                        CircuitBreaker.State.CLOSED -> 0.0
                        else -> -1.0
                    }
                }
            }
        }

        return registry
    }

    @Bean
    fun retryRegistry(): RetryRegistry {
        // Inventory retry - quick retries
        val inventoryRetryConfig = RetryConfig.custom<Any>()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .exponentialBackoffMultiplier(1.5)
            .retryExceptions(
                java.net.ConnectException::class.java,
                java.util.concurrent.TimeoutException::class.java,
                io.grpc.StatusRuntimeException::class.java
            )
            .ignoreExceptions(
                com.traffictacos.reservation.grpc.GrpcException::class.java // Don't retry business logic errors
            )
            .build()

        // DynamoDB retry - with exponential backoff
        val dynamoRetryConfig = RetryConfig.custom<Any>()
            .maxAttempts(4)
            .waitDuration(Duration.ofMillis(100))
            .exponentialBackoffMultiplier(2.0)
            .retryExceptions(
                software.amazon.awssdk.core.exception.SdkException::class.java,
                software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException::class.java
            )
            .ignoreExceptions(
                software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException::class.java,
                software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException::class.java
            )
            .build()

        // EventBridge retry - aggressive retries (eventual consistency)
        val eventBridgeRetryConfig = RetryConfig.custom<Any>()
            .maxAttempts(5)
            .waitDuration(Duration.ofSeconds(1))
            .exponentialBackoffMultiplier(2.0)
            .build()

        val registry = RetryRegistry.ofDefaults()
        registry.retry("inventory", inventoryRetryConfig)
        registry.retry("dynamodb", dynamoRetryConfig)
        registry.retry("eventbridge", eventBridgeRetryConfig)

        return registry
    }

    @Bean
    fun timeLimiterRegistry(): TimeLimiterRegistry {
        // Inventory timeout - strict (user-facing)
        val inventoryTimeoutConfig = TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(250)) // 250ms max for inventory calls
            .cancelRunningFuture(true)
            .build()

        // DynamoDB timeout - moderate
        val dynamoTimeoutConfig = TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofSeconds(2)) // 2s max for DynamoDB operations
            .cancelRunningFuture(true)
            .build()

        // EventBridge timeout - generous (async operation)
        val eventBridgeTimeoutConfig = TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofSeconds(5)) // 5s max for event publishing
            .cancelRunningFuture(true)
            .build()

        val registry = TimeLimiterRegistry.ofDefaults()
        registry.timeLimiter("inventory", inventoryTimeoutConfig)
        registry.timeLimiter("dynamodb", dynamoTimeoutConfig)
        registry.timeLimiter("eventbridge", eventBridgeTimeoutConfig)

        return registry
    }

    @Bean
    fun rateLimiterRegistry(): RateLimiterRegistry {
        // Global rate limiting for the service
        val globalRateLimiterConfig = RateLimiterConfig.custom()
            .limitForPeriod(1000) // 1000 requests
            .limitRefreshPeriod(Duration.ofMinutes(1)) // per minute
            .timeoutDuration(Duration.ofMillis(100)) // wait 100ms for permission
            .build()

        // Per-user rate limiting (stricter)
        val userRateLimiterConfig = RateLimiterConfig.custom()
            .limitForPeriod(50) // 50 requests
            .limitRefreshPeriod(Duration.ofMinutes(1)) // per minute
            .timeoutDuration(Duration.ofMillis(50)) // wait 50ms for permission
            .build()

        // Inventory service rate limiting
        val inventoryRateLimiterConfig = RateLimiterConfig.custom()
            .limitForPeriod(500) // 500 calls
            .limitRefreshPeriod(Duration.ofMinutes(1)) // per minute
            .timeoutDuration(Duration.ofMillis(100))
            .build()

        val registry = RateLimiterRegistry.ofDefaults()
        registry.rateLimiter("global", globalRateLimiterConfig)
        registry.rateLimiter("user", userRateLimiterConfig)
        registry.rateLimiter("inventory", inventoryRateLimiterConfig)

        return registry
    }

    @Bean
    fun bulkheadRegistry(): BulkheadRegistry {
        // Inventory service bulkhead - limit concurrent calls
        val inventoryBulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(20) // Max 20 concurrent inventory calls
            .maxWaitDuration(Duration.ofMillis(100)) // Wait 100ms for permission
            .build()

        // DynamoDB bulkhead - higher concurrency
        val dynamoBulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(50) // Max 50 concurrent DynamoDB calls
            .maxWaitDuration(Duration.ofMillis(50))
            .build()

        // EventBridge bulkhead - lower concurrency (it's async anyway)
        val eventBridgeBulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(10) // Max 10 concurrent event publishing
            .maxWaitDuration(Duration.ofMillis(200))
            .build()

        val registry = BulkheadRegistry.ofDefaults()
        registry.bulkhead("inventory", inventoryBulkheadConfig)
        registry.bulkhead("dynamodb", dynamoBulkheadConfig)
        registry.bulkhead("eventbridge", eventBridgeBulkheadConfig)

        return registry
    }
}