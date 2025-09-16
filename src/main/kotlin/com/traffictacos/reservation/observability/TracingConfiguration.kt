package com.traffictacos.reservation.observability

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.SpanBuilder
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono
import reactor.core.publisher.Flux
import java.util.*

@Aspect
@Configuration
class TracingConfiguration(
    private val tracer: Tracer
) {
    private val logger = LoggerFactory.getLogger(TracingConfiguration::class.java)

    @Around("execution(* com.traffictacos.reservation.service.*.*(..))")
    fun traceServiceMethods(joinPoint: ProceedingJoinPoint): Any? {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        val spanName = "$className.$methodName"

        return tracer.nextSpan()
            .name(spanName)
            .tag("component", "service")
            .tag("class", className)
            .tag("method", methodName)
            .start()
            .use { span ->
                try {
                    // Add trace context to MDC for logging
                    MDC.put("traceId", span.context().traceId())
                    MDC.put("spanId", span.context().spanId())
                    
                    val result = joinPoint.proceed()
                    
                    // Handle reactive types
                    when (result) {
                        is Mono<*> -> {
                            result.doOnSuccess { span.tag("success", "true") }
                                .doOnError { error ->
                                    span.tag("error", error.javaClass.simpleName)
                                    span.tag("error.message", error.message ?: "Unknown error")
                                }
                                .doFinally { 
                                    MDC.remove("traceId")
                                    MDC.remove("spanId")
                                }
                        }
                        is Flux<*> -> {
                            result.doOnComplete { span.tag("success", "true") }
                                .doOnError { error ->
                                    span.tag("error", error.javaClass.simpleName)
                                    span.tag("error.message", error.message ?: "Unknown error")
                                }
                                .doFinally { 
                                    MDC.remove("traceId")
                                    MDC.remove("spanId")
                                }
                        }
                        else -> {
                            span.tag("success", "true")
                            MDC.remove("traceId")
                            MDC.remove("spanId")
                        }
                    }
                    
                    result
                } catch (e: Exception) {
                    span.tag("error", e.javaClass.simpleName)
                    span.tag("error.message", e.message ?: "Unknown error")
                    MDC.remove("traceId")
                    MDC.remove("spanId")
                    throw e
                }
            }
    }

    @Around("execution(* com.traffictacos.reservation.repository.*.*(..))")
    fun traceRepositoryMethods(joinPoint: ProceedingJoinPoint): Any? {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        val spanName = "$className.$methodName"

        return tracer.nextSpan()
            .name(spanName)
            .tag("component", "repository")
            .tag("class", className)
            .tag("method", methodName)
            .tag("db.type", "dynamodb")
            .start()
            .use { span ->
                try {
                    val result = joinPoint.proceed()
                    
                    // Handle reactive types
                    when (result) {
                        is Mono<*> -> {
                            result.doOnSuccess { span.tag("success", "true") }
                                .doOnError { error ->
                                    span.tag("error", error.javaClass.simpleName)
                                    span.tag("error.message", error.message ?: "Unknown error")
                                }
                        }
                        is Flux<*> -> {
                            result.doOnComplete { span.tag("success", "true") }
                                .doOnError { error ->
                                    span.tag("error", error.javaClass.simpleName)
                                    span.tag("error.message", error.message ?: "Unknown error")
                                }
                        }
                        else -> {
                            span.tag("success", "true")
                        }
                    }
                    
                    result
                } catch (e: Exception) {
                    span.tag("error", e.javaClass.simpleName)
                    span.tag("error.message", e.message ?: "Unknown error")
                    throw e
                }
            }
    }

    @Around("execution(* com.traffictacos.reservation.grpc.*.*(..))")
    fun traceGrpcMethods(joinPoint: ProceedingJoinPoint): Any? {
        val className = joinPoint.signature.declaringType.simpleName
        val methodName = joinPoint.signature.name
        val spanName = "$className.$methodName"

        return tracer.nextSpan()
            .name(spanName)
            .tag("component", "grpc")
            .tag("class", className)
            .tag("method", methodName)
            .tag("rpc.system", "grpc")
            .tag("rpc.service", "inventory")
            .start()
            .use { span ->
                try {
                    val result = joinPoint.proceed()
                    
                    // Handle reactive types
                    when (result) {
                        is Mono<*> -> {
                            result.doOnSuccess { 
                                span.tag("success", "true")
                                span.tag("rpc.grpc.status_code", "OK")
                            }
                            .doOnError { error ->
                                span.tag("error", error.javaClass.simpleName)
                                span.tag("error.message", error.message ?: "Unknown error")
                                span.tag("rpc.grpc.status_code", "INTERNAL")
                            }
                        }
                        else -> {
                            span.tag("success", "true")
                            span.tag("rpc.grpc.status_code", "OK")
                        }
                    }
                    
                    result
                } catch (e: Exception) {
                    span.tag("error", e.javaClass.simpleName)
                    span.tag("error.message", e.message ?: "Unknown error")
                    span.tag("rpc.grpc.status_code", "INTERNAL")
                    throw e
                }
            }
    }
}