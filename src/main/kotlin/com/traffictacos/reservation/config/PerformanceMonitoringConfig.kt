package com.traffictacos.reservation.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

@Aspect
@Component
class PerformanceMonitoringConfig(
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(PerformanceMonitoringConfig::class.java)

    // Service method performance monitoring
    @Around("execution(* com.traffictacos.reservation.service.*.*(..))")
    fun monitorServiceMethods(joinPoint: ProceedingJoinPoint): Any? {
        val methodName = joinPoint.signature.name
        val className = joinPoint.signature.declaringType.simpleName

        val timer = Timer.builder("service.method.duration")
            .tag("class", className)
            .tag("method", methodName)
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(meterRegistry)

        val sample = Timer.start(meterRegistry)

        return try {
            val result = joinPoint.proceed()

            sample.stop(timer)

            // Log slow methods
            val duration = sample.duration()
            if (duration > Duration.ofMillis(1000)) {
                logger.warn("Slow service method: {}.{} took {}ms",
                    className, methodName, duration.toMillis())
            }

            result
        } catch (e: Exception) {
            // Record error metrics
            Counter.builder("service.method.error")
                .tag("class", className)
                .tag("method", methodName)
                .tag("exception", e.javaClass.simpleName)
                .register(meterRegistry)
                .increment()

            sample.stop(timer)
            throw e
        }
    }

    // Repository method performance monitoring
    @Around("execution(* com.traffictacos.reservation.repository.*.*(..))")
    fun monitorRepositoryMethods(joinPoint: ProceedingJoinPoint): Any? {
        val methodName = joinPoint.signature.name
        val className = joinPoint.signature.declaringType.simpleName

        val timer = Timer.builder("repository.method.duration")
            .tag("class", className)
            .tag("method", methodName)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)

        val sample = Timer.start(meterRegistry)

        return try {
            val result = joinPoint.proceed()

            sample.stop(timer)

            // Log slow database operations
            val duration = sample.duration()
            if (duration > Duration.ofMillis(500)) {
                logger.warn("Slow repository method: {}.{} took {}ms",
                    className, methodName, duration.toMillis())
            }

            result
        } catch (e: Exception) {
            // Record database error metrics
            Counter.builder("repository.method.error")
                .tag("class", className)
                .tag("method", methodName)
                .tag("exception", e.javaClass.simpleName)
                .register(meterRegistry)
                .increment()

            sample.stop(timer)
            throw e
        }
    }

    // gRPC call performance monitoring
    @Around("execution(* com.traffictacos.reservation.grpc.*.*(..))")
    fun monitorGrpcCalls(joinPoint: ProceedingJoinPoint): Any? {
        val methodName = joinPoint.signature.name
        val className = joinPoint.signature.declaringType.simpleName

        val timer = Timer.builder("grpc.call.duration")
            .tag("class", className)
            .tag("method", methodName)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)

        val sample = Timer.start(meterRegistry)

        return try {
            val result = joinPoint.proceed()

            sample.stop(timer)

            // Log slow gRPC calls
            val duration = sample.duration()
            if (duration > Duration.ofMillis(200)) {
                logger.warn("Slow gRPC call: {}.{} took {}ms",
                    className, methodName, duration.toMillis())
            }

            result
        } catch (e: Exception) {
            // Record gRPC error metrics
            Counter.builder("grpc.call.error")
                .tag("class", className)
                .tag("method", methodName)
                .tag("exception", e.javaClass.simpleName)
                .register(meterRegistry)
                .increment()

            sample.stop(timer)
            throw e
        }
    }
}
