package com.traffictacos.reservation.observability

import com.fasterxml.jackson.databind.ObjectMapper
import net.logstash.logback.argument.StructuredArguments
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.AfterReturning
import org.aspectj.lang.annotation.AfterThrowing
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.*

@Aspect
@Configuration
class StructuredLoggingConfiguration(
    private val objectMapper: ObjectMapper
) {
    private val logger: Logger = LoggerFactory.getLogger(StructuredLoggingConfiguration::class.java)

    companion object {
        const val REQUEST_ID = "requestId"
        const val USER_ID = "userId"
        const val SESSION_ID = "sessionId"
        const val CORRELATION_ID = "correlationId"
        const val OPERATION = "operation"
        const val COMPONENT = "component"
        const val BUSINESS_EVENT = "businessEvent"
    }

    @Before("execution(* com.traffictacos.reservation.controller.*.*(..))")
    fun logControllerEntry(joinPoint: JoinPoint) {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        
        // Extract request information
        val args = joinPoint.args
        val exchange = args.find { it is ServerWebExchange } as? ServerWebExchange
        
        val requestId = UUID.randomUUID().toString()
        MDC.put(REQUEST_ID, requestId)
        MDC.put(COMPONENT, "controller")
        MDC.put(OPERATION, "$className.$methodName")
        
        // Extract user information if available
        exchange?.let { ex ->
            ex.request.headers.getFirst("X-User-ID")?.let { userId ->
                MDC.put(USER_ID, userId)
            }
            ex.request.headers.getFirst("X-Correlation-ID")?.let { correlationId ->
                MDC.put(CORRELATION_ID, correlationId)
            }
        }

        logger.info("HTTP request started",
            StructuredArguments.keyValue("method", exchange?.request?.method?.name()),
            StructuredArguments.keyValue("uri", exchange?.request?.uri?.path),
            StructuredArguments.keyValue("controller", className),
            StructuredArguments.keyValue("action", methodName),
            StructuredArguments.keyValue("timestamp", Instant.now().toString())
        )
    }

    @AfterReturning(pointcut = "execution(* com.traffictacos.reservation.controller.*.*(..))", returning = "result")
    fun logControllerSuccess(joinPoint: JoinPoint, result: Any?) {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name

        when (result) {
            is Mono<*> -> {
                result.doOnSuccess { response ->
                    logger.info("HTTP request completed successfully",
                        StructuredArguments.keyValue("controller", className),
                        StructuredArguments.keyValue("action", methodName),
                        StructuredArguments.keyValue("status", "success"),
                        StructuredArguments.keyValue("timestamp", Instant.now().toString())
                    )
                }.subscribe()
            }
            else -> {
                logger.info("HTTP request completed successfully",
                    StructuredArguments.keyValue("controller", className),
                    StructuredArguments.keyValue("action", methodName),
                    StructuredArguments.keyValue("status", "success"),
                    StructuredArguments.keyValue("timestamp", Instant.now().toString())
                )
            }
        }
    }

    @AfterThrowing(pointcut = "execution(* com.traffictacos.reservation.controller.*.*(..))", throwing = "error")
    fun logControllerError(joinPoint: JoinPoint, error: Throwable) {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name

        logger.error("HTTP request failed",
            StructuredArguments.keyValue("controller", className),
            StructuredArguments.keyValue("action", methodName),
            StructuredArguments.keyValue("status", "error"),
            StructuredArguments.keyValue("error_type", error.javaClass.simpleName),
            StructuredArguments.keyValue("error_message", error.message),
            StructuredArguments.keyValue("timestamp", Instant.now().toString()),
            error
        )
    }

    @Before("execution(* com.traffictacos.reservation.service.*.*(..))")
    fun logServiceEntry(joinPoint: JoinPoint) {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        
        MDC.put(COMPONENT, "service")
        MDC.put(OPERATION, "$className.$methodName")

        // Log business operations with structured data
        val businessEvent = mapMethodToBusinessEvent(className, methodName)
        businessEvent?.let { MDC.put(BUSINESS_EVENT, it) }

        logger.info("Service operation started",
            StructuredArguments.keyValue("service", className),
            StructuredArguments.keyValue("method", methodName),
            StructuredArguments.keyValue("business_event", businessEvent),
            StructuredArguments.keyValue("timestamp", Instant.now().toString())
        )
    }

    @AfterReturning(pointcut = "execution(* com.traffictacos.reservation.service.*.*(..))", returning = "result")
    fun logServiceSuccess(joinPoint: JoinPoint, result: Any?) {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        val businessEvent = MDC.get(BUSINESS_EVENT)

        when (result) {
            is Mono<*> -> {
                result.doOnSuccess { response ->
                    logger.info("Service operation completed successfully",
                        StructuredArguments.keyValue("service", className),
                        StructuredArguments.keyValue("method", methodName),
                        StructuredArguments.keyValue("business_event", businessEvent),
                        StructuredArguments.keyValue("status", "success"),
                        StructuredArguments.keyValue("timestamp", Instant.now().toString())
                    )
                }.subscribe()
            }
            else -> {
                logger.info("Service operation completed successfully",
                    StructuredArguments.keyValue("service", className),
                    StructuredArguments.keyValue("method", methodName),
                    StructuredArguments.keyValue("business_event", businessEvent),
                    StructuredArguments.keyValue("status", "success"),
                    StructuredArguments.keyValue("timestamp", Instant.now().toString())
                )
            }
        }
    }

    @AfterThrowing(pointcut = "execution(* com.traffictacos.reservation.service.*.*(..))", throwing = "error")
    fun logServiceError(joinPoint: JoinPoint, error: Throwable) {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        val businessEvent = MDC.get(BUSINESS_EVENT)

        logger.error("Service operation failed",
            StructuredArguments.keyValue("service", className),
            StructuredArguments.keyValue("method", methodName),
            StructuredArguments.keyValue("business_event", businessEvent),
            StructuredArguments.keyValue("status", "error"),
            StructuredArguments.keyValue("error_type", error.javaClass.simpleName),
            StructuredArguments.keyValue("error_message", error.message),
            StructuredArguments.keyValue("timestamp", Instant.now().toString()),
            error
        )
    }

    @Before("execution(* com.traffictacos.reservation.repository.*.*(..))")
    fun logRepositoryEntry(joinPoint: JoinPoint) {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        
        MDC.put(COMPONENT, "repository")
        MDC.put(OPERATION, "$className.$methodName")

        logger.debug("Repository operation started",
            StructuredArguments.keyValue("repository", className),
            StructuredArguments.keyValue("method", methodName),
            StructuredArguments.keyValue("db_type", "dynamodb"),
            StructuredArguments.keyValue("timestamp", Instant.now().toString())
        )
    }

    @Before("execution(* com.traffictacos.reservation.grpc.*.*(..))")
    fun logGrpcEntry(joinPoint: JoinPoint) {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        
        MDC.put(COMPONENT, "grpc")
        MDC.put(OPERATION, "$className.$methodName")

        logger.info("gRPC call started",
            StructuredArguments.keyValue("grpc_client", className),
            StructuredArguments.keyValue("method", methodName),
            StructuredArguments.keyValue("protocol", "grpc"),
            StructuredArguments.keyValue("timestamp", Instant.now().toString())
        )
    }

    private fun mapMethodToBusinessEvent(className: String, methodName: String): String? {
        return when {
            className == "ReservationService" && methodName == "createReservation" -> "RESERVATION_CREATION_REQUESTED"
            className == "ReservationService" && methodName == "confirmReservation" -> "RESERVATION_CONFIRMATION_REQUESTED"
            className == "ReservationService" && methodName == "cancelReservation" -> "RESERVATION_CANCELLATION_REQUESTED"
            className == "IdempotencyService" && methodName == "executeIdempotent" -> "IDEMPOTENCY_CHECK_PERFORMED"
            className == "OutboxEventPublisher" && methodName == "publishEvent" -> "DOMAIN_EVENT_PUBLISHED"
            else -> null
        }
    }

    fun logBusinessEvent(
        eventType: String,
        entityId: String,
        entityType: String,
        details: Map<String, Any> = emptyMap()
    ) {
        logger.info("Business event occurred",
            StructuredArguments.keyValue("event_type", eventType),
            StructuredArguments.keyValue("entity_id", entityId),
            StructuredArguments.keyValue("entity_type", entityType),
            StructuredArguments.keyValue("details", details),
            StructuredArguments.keyValue("timestamp", Instant.now().toString())
        )
    }

    fun logSecurityEvent(
        eventType: String,
        userId: String?,
        details: Map<String, Any> = emptyMap()
    ) {
        logger.warn("Security event detected",
            StructuredArguments.keyValue("security_event", eventType),
            StructuredArguments.keyValue("user_id", userId),
            StructuredArguments.keyValue("details", details),
            StructuredArguments.keyValue("timestamp", Instant.now().toString())
        )
    }

    fun logPerformanceEvent(
        operation: String,
        durationMs: Long,
        threshold: Long,
        details: Map<String, Any> = emptyMap()
    ) {
        val logLevel = if (durationMs > threshold) "WARN" else "INFO"
        
        logger.info("Performance metric recorded",
            StructuredArguments.keyValue("operation", operation),
            StructuredArguments.keyValue("duration_ms", durationMs),
            StructuredArguments.keyValue("threshold_ms", threshold),
            StructuredArguments.keyValue("performance_status", if (durationMs > threshold) "SLOW" else "NORMAL"),
            StructuredArguments.keyValue("details", details),
            StructuredArguments.keyValue("timestamp", Instant.now().toString())
        )
    }
}