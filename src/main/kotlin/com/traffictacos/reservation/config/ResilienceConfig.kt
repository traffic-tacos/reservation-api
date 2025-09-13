package com.traffictacos.reservation.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class ResilienceConfig {

    @Bean
    fun circuitBreakerRegistry(): CircuitBreakerRegistry {
        val config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofMillis(10000))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofMillis(2000))
            .build()

        return CircuitBreakerRegistry.of(config)
    }

    @Bean
    fun retryRegistry(): RetryRegistry {
        val config = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(1000))
            .retryExceptions(
                java.net.ConnectException::class.java,
                java.net.SocketTimeoutException::class.java,
                io.grpc.StatusRuntimeException::class.java
            )
            .ignoreExceptions(
                IllegalArgumentException::class.java,
                IllegalStateException::class.java
            )
            .build()

        return RetryRegistry.of(config)
    }

    @Bean
    fun timeLimiterRegistry(): TimeLimiterRegistry {
        val config = TimeLimiterConfig.custom()
            .timeoutDuration(Duration.ofMillis(600))
            .cancelRunningFuture(true)
            .build()

        return TimeLimiterRegistry.of(config)
    }

    // Specific configurations for different services
    @Bean
    fun inventoryGrpcCircuitBreaker(circuitBreakerRegistry: CircuitBreakerRegistry): io.github.resilience4j.circuitbreaker.CircuitBreaker {
        val config = CircuitBreakerConfig.from(circuitBreakerRegistry.config)
            .failureRateThreshold(30.0f) // Lower threshold for gRPC
            .waitDurationInOpenState(Duration.ofMillis(15000)) // Longer wait for external service
            .build()

        return circuitBreakerRegistry.circuitBreaker("inventoryGrpc", config)
    }

    @Bean
    fun inventoryGrpcRetry(retryRegistry: RetryRegistry): io.github.resilience4j.retry.Retry {
        val config = RetryConfig.from(retryRegistry.config)
            .maxAttempts(2) // Fewer retries for gRPC
            .waitDuration(Duration.ofMillis(500))
            .build()

        return retryRegistry.retry("inventoryGrpc", config)
    }

    @Bean
    fun inventoryGrpcTimeLimiter(timeLimiterRegistry: TimeLimiterRegistry): io.github.resilience4j.timelimiter.TimeLimiter {
        val config = TimeLimiterConfig.from(timeLimiterRegistry.config)
            .timeoutDuration(Duration.ofMillis(250)) // Shorter timeout for gRPC
            .build()

        return timeLimiterRegistry.timeLimiter("inventoryGrpc", config)
    }

    @Bean
    fun dynamoDbCircuitBreaker(circuitBreakerRegistry: CircuitBreakerRegistry): io.github.resilience4j.circuitbreaker.CircuitBreaker {
        val config = CircuitBreakerConfig.from(circuitBreakerRegistry.config)
            .failureRateThreshold(20.0f) // Very low threshold for database
            .waitDurationInOpenState(Duration.ofMillis(30000)) // Long wait for database recovery
            .build()

        return circuitBreakerRegistry.circuitBreaker("dynamoDb", config)
    }

    @Bean
    fun dynamoDbTimeLimiter(timeLimiterRegistry: TimeLimiterRegistry): io.github.resilience4j.timelimiter.TimeLimiter {
        val config = TimeLimiterConfig.from(timeLimiterRegistry.config)
            .timeoutDuration(Duration.ofMillis(1000)) // 1 second for database operations
            .build()

        return timeLimiterRegistry.timeLimiter("dynamoDb", config)
    }
}
