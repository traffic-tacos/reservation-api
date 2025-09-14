package com.traffictacos.reservation.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Profile
import java.time.Duration

@Aspect
@Component
@Profile("!local")
class PerformanceMonitoringConfig(
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(PerformanceMonitoringConfig::class.java)

    // Service method performance monitoring
    @Around("execution(* com.traffictacos.reservation.service.*.*(..))")
    fun monitorServiceMethods(joinPoint: ProceedingJoinPoint): Any? {
        val methodName = joinPoint.signature.name
        val className = joinPoint.signature.declaringType.simpleName

        val sample = Timer.start(meterRegistry)

        return try {
            val result = joinPoint.proceed()
            sample.stop(Timer.builder("service.method.duration")
                .tag("class", className)
                .tag("method", methodName)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry))

            // Record success
            Counter.builder("service.method.success")
                .tag("class", className)
                .tag("method", methodName)
                .register(meterRegistry)
                .increment()

            result
        } catch (e: Exception) {
            sample.stop(Timer.builder("service.method.duration")
                .tag("class", className)
                .tag("method", methodName)
                .register(meterRegistry))

            // Record failure
            Counter.builder("service.method.error")
                .tag("class", className)
                .tag("method", methodName)
                .tag("exception", e.javaClass.simpleName)
                .register(meterRegistry)
                .increment()

            throw e
        }
    }

    // Controller method performance monitoring
    @Around("execution(* com.traffictacos.reservation.controller.*.*(..))")
    fun monitorControllerMethods(joinPoint: ProceedingJoinPoint): Any? {
        val methodName = joinPoint.signature.name
        val className = joinPoint.signature.declaringType.simpleName

        val sample = Timer.start(meterRegistry)

        return try {
            val result = joinPoint.proceed()
            sample.stop(Timer.builder("controller.method.duration")
                .tag("class", className)
                .tag("method", methodName)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry))
            result
        } catch (e: Exception) {
            sample.stop(Timer.builder("controller.method.duration")
                .tag("class", className)
                .tag("method", methodName)
                .register(meterRegistry))
            throw e
        }
    }

    // Repository method performance monitoring
    @Around("execution(* com.traffictacos.reservation.repository.*.*(..))")
    fun monitorRepositoryMethods(joinPoint: ProceedingJoinPoint): Any? {
        val methodName = joinPoint.signature.name
        val className = joinPoint.signature.declaringType.simpleName

        val sample = Timer.start(meterRegistry)

        return try {
            val result = joinPoint.proceed()
            sample.stop(Timer.builder("repository.method.duration")
                .tag("class", className)
                .tag("method", methodName)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry))
            result
        } catch (e: Exception) {
            sample.stop(Timer.builder("repository.method.duration")
                .tag("class", className)
                .tag("method", methodName)
                .register(meterRegistry))
            throw e
        }
    }
}