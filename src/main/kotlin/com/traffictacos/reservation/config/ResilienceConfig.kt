package com.traffictacos.reservation.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Duration

@Configuration
@Profile("!local")
class ResilienceConfig {

    @Bean
    fun circuitBreakerRegistry(): CircuitBreakerRegistry {
        val config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofMillis(10000))
            .permittedNumberOfCallsInHalfOpenState(3)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .build()

        return CircuitBreakerRegistry.of(config)
    }

    @Bean
    fun retryRegistry(): RetryRegistry {
        val config = RetryConfig.custom<Any>()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(1000))
            .retryExceptions(java.net.ConnectException::class.java)
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
}