package com.traffictacos.reservation.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
@Tag(name = "Health", description = "Health check endpoints")
class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Basic health check", description = "Returns service health status")
    fun health(): Map<String, String> {
        return mapOf(
            "status" to "UP",
            "service" to "reservation-api",
            "timestamp" to java.time.Instant.now().toString()
        )
    }

    @GetMapping("/info")
    @Operation(summary = "Service information", description = "Returns service information")
    fun info(): Map<String, Any> {
        return mapOf(
            "service" to "reservation-api",
            "version" to "1.0.0",
            "description" to "Ticket Reservation API Service",
            "framework" to "Spring Boot WebFlux",
            "java" to System.getProperty("java.version"),
            "kotlin" to KotlinVersion.CURRENT.toString()
        )
    }
}