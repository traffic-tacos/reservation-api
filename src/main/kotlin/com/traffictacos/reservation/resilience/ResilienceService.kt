package com.traffictacos.reservation.resilience

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.function.Supplier

private val logger = KotlinLogging.logger {}

@Service
class ResilienceService(
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val retryRegistry: RetryRegistry,
    private val timeLimiterRegistry: TimeLimiterRegistry,
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val bulkheadRegistry: BulkheadRegistry,
    private val meterRegistry: MeterRegistry
) {

    /**
     * Execute inventory service calls with full resilience patterns
     */
    fun <T> executeInventoryCall(operation: Supplier<Mono<T>>, operationName: String = "inventory-call"): Mono<T> {
        logger.debug { "Executing inventory call with resilience patterns: $operationName" }
        
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory")
        val retry = retryRegistry.retry("inventory")
        val timeLimiter = timeLimiterRegistry.timeLimiter("inventory")
        val rateLimiter = rateLimiterRegistry.rateLimiter("inventory")
        val bulkhead = bulkheadRegistry.bulkhead("inventory")

        return operation.get()
            .transformDeferred(TimeLimiterOperator.of(timeLimiter))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .transformDeferred(RateLimiterOperator.of(rateLimiter))
            .transformDeferred(RetryOperator.of(retry))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess { 
                logger.debug { "Inventory call succeeded: $operationName" }
                meterRegistry.counter("resilience.inventory.success", "operation", operationName).increment()
            }
            .doOnError { error ->
                logger.warn(error) { "Inventory call failed: $operationName" }
                meterRegistry.counter("resilience.inventory.failure", "operation", operationName, "error", error.javaClass.simpleName).increment()
            }
            .onErrorResume { error ->
                handleInventoryFallback(error, operationName)
            }
    }

    /**
     * Execute DynamoDB operations with resilience patterns
     */
    fun <T> executeDynamoCall(operation: Supplier<Mono<T>>, operationName: String = "dynamo-call"): Mono<T> {
        logger.debug { "Executing DynamoDB call with resilience patterns: $operationName" }
        
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker("dynamodb")
        val retry = retryRegistry.retry("dynamodb")
        val timeLimiter = timeLimiterRegistry.timeLimiter("dynamodb")
        val bulkhead = bulkheadRegistry.bulkhead("dynamodb")

        return operation.get()
            .transformDeferred(TimeLimiterOperator.of(timeLimiter))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .transformDeferred(RetryOperator.of(retry))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess { 
                logger.debug { "DynamoDB call succeeded: $operationName" }
                meterRegistry.counter("resilience.dynamodb.success", "operation", operationName).increment()
            }
            .doOnError { error ->
                logger.error(error) { "DynamoDB call failed: $operationName" }
                meterRegistry.counter("resilience.dynamodb.failure", "operation", operationName, "error", error.javaClass.simpleName).increment()
            }
    }

    /**
     * Execute DynamoDB operations that return Flux with resilience patterns
     */
    fun <T> executeDynamoFluxCall(operation: Supplier<Flux<T>>, operationName: String = "dynamo-flux-call"): Flux<T> {
        logger.debug { "Executing DynamoDB Flux call with resilience patterns: $operationName" }
        
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker("dynamodb")
        val retry = retryRegistry.retry("dynamodb")
        val timeLimiter = timeLimiterRegistry.timeLimiter("dynamodb")
        val bulkhead = bulkheadRegistry.bulkhead("dynamodb")

        return operation.get()
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(BulkheadOperator.of(bulkhead))
            .transform(RetryOperator.of(retry))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnComplete { 
                logger.debug { "DynamoDB Flux call succeeded: $operationName" }
                meterRegistry.counter("resilience.dynamodb.success", "operation", operationName).increment()
            }
            .doOnError { error ->
                logger.error(error) { "DynamoDB Flux call failed: $operationName" }
                meterRegistry.counter("resilience.dynamodb.failure", "operation", operationName, "error", error.javaClass.simpleName).increment()
            }
    }

    /**
     * Execute EventBridge operations with resilience patterns
     */
    fun <T> executeEventBridgeCall(operation: Supplier<Mono<T>>, operationName: String = "eventbridge-call"): Mono<T> {
        logger.debug { "Executing EventBridge call with resilience patterns: $operationName" }
        
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker("eventbridge")
        val retry = retryRegistry.retry("eventbridge")
        val timeLimiter = timeLimiterRegistry.timeLimiter("eventbridge")
        val bulkhead = bulkheadRegistry.bulkhead("eventbridge")

        return operation.get()
            .transformDeferred(TimeLimiterOperator.of(timeLimiter))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .transformDeferred(RetryOperator.of(retry))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess { 
                logger.debug { "EventBridge call succeeded: $operationName" }
                meterRegistry.counter("resilience.eventbridge.success", "operation", operationName).increment()
            }
            .doOnError { error ->
                logger.warn(error) { "EventBridge call failed: $operationName" }
                meterRegistry.counter("resilience.eventbridge.failure", "operation", operationName, "error", error.javaClass.simpleName).increment()
            }
            .onErrorResume { error ->
                handleEventBridgeFallback(error, operationName)
            }
    }

    /**
     * Apply rate limiting to user requests
     */
    fun <T> executeWithUserRateLimit(userId: String, operation: Supplier<Mono<T>>): Mono<T> {
        val rateLimiter = rateLimiterRegistry.rateLimiter("user")
        
        return operation.get()
            .transformDeferred(RateLimiterOperator.of(rateLimiter))
            .doOnSuccess { 
                meterRegistry.counter("resilience.ratelimit.user.success", "user", userId).increment()
            }
            .doOnError { error ->
                meterRegistry.counter("resilience.ratelimit.user.failure", "user", userId, "error", error.javaClass.simpleName).increment()
            }
    }

    /**
     * Apply global rate limiting
     */
    fun <T> executeWithGlobalRateLimit(operation: Supplier<Mono<T>>): Mono<T> {
        val rateLimiter = rateLimiterRegistry.rateLimiter("global")
        
        return operation.get()
            .transformDeferred(RateLimiterOperator.of(rateLimiter))
            .doOnSuccess { 
                meterRegistry.counter("resilience.ratelimit.global.success").increment()
            }
            .doOnError { error ->
                meterRegistry.counter("resilience.ratelimit.global.failure", "error", error.javaClass.simpleName).increment()
            }
    }

    /**
     * Fallback for inventory service failures
     */
    private fun <T> handleInventoryFallback(error: Throwable, operationName: String): Mono<T> {
        logger.warn(error) { "Inventory service fallback triggered for: $operationName" }
        meterRegistry.counter("resilience.inventory.fallback", "operation", operationName).increment()
        
        return when (operationName) {
            "checkAvailability" -> {
                // Fallback: assume not available for safety
                logger.warn { "Inventory service unavailable, defaulting to 'not available' for safety" }
                Mono.error(com.traffictacos.reservation.exception.UpstreamTimeoutException(
                    "Inventory service temporarily unavailable", error
                ))
            }
            "commitReservation" -> {
                // Fallback: fail fast, don't commit without inventory confirmation
                logger.error { "Cannot commit reservation without inventory service confirmation" }
                Mono.error(com.traffictacos.reservation.exception.UpstreamTimeoutException(
                    "Cannot confirm reservation - inventory service unavailable", error
                ))
            }
            "releaseHold" -> {
                // Fallback: log for manual cleanup but don't fail the user operation
                logger.warn { "Failed to release inventory hold - will require manual cleanup" }
                Mono.empty() // Continue with cancellation even if inventory release failed
            }
            else -> Mono.error(error)
        }
    }

    /**
     * Fallback for EventBridge failures
     */
    private fun <T> handleEventBridgeFallback(error: Throwable, operationName: String): Mono<T> {
        logger.warn(error) { "EventBridge fallback triggered for: $operationName" }
        meterRegistry.counter("resilience.eventbridge.fallback", "operation", operationName).increment()
        
        // EventBridge failures are not critical - log and continue
        logger.warn { "Event publishing failed, continuing without events (eventual consistency)" }
        return Mono.empty() // Return empty to continue the operation
    }

    /**
     * Get resilience metrics for monitoring dashboard
     */
    fun getResilienceMetrics(): Map<String, Any> {
        val metrics = mutableMapOf<String, Any>()
        
        // Circuit breaker states
        circuitBreakerRegistry.allCircuitBreakers.forEach { cb ->
            metrics["circuitbreaker.${cb.name}.state"] = cb.state.name
            metrics["circuitbreaker.${cb.name}.failure_rate"] = cb.metrics.failureRate
            metrics["circuitbreaker.${cb.name}.calls"] = cb.metrics.numberOfCalls
        }
        
        // Rate limiter metrics
        rateLimiterRegistry.allRateLimiters.forEach { rl ->
            metrics["ratelimiter.${rl.name}.available_permissions"] = rl.metrics.availablePermissions
        }
        
        // Bulkhead metrics
        bulkheadRegistry.allBulkheads.forEach { bh ->
            metrics["bulkhead.${bh.name}.available_concurrent_calls"] = bh.metrics.availableConcurrentCalls
            metrics["bulkhead.${bh.name}.max_allowed_concurrent_calls"] = bh.metrics.maxAllowedConcurrentCalls
        }
        
        return metrics
    }
}