package com.traffictacos.reservation.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import javax.annotation.PostConstruct

@Configuration
@Profile("!local")
class ObservabilityConfig(
    private val meterRegistry: MeterRegistry,
    @Value("\${spring.application.name}") private val applicationName: String
) {
    private val logger = LoggerFactory.getLogger(ObservabilityConfig::class.java)

    @PostConstruct
    fun initMetrics() {
        // Custom application metrics
        meterRegistry.gauge("reservation_api_info", Tags.of("version", "1.0.0", "service", applicationName), 1.0)
    }

    @Scheduled(fixedRate = 30000) // Every 30 seconds
    fun logHealthMetrics() {
        try {
            val httpRequests = meterRegistry.find("http.server.requests")
                .meters()
                .groupBy { it.id.tags.firstOrNull { tag -> tag.key == "uri" }?.value }
                .mapValues { (_, meters) ->
                    meters.sumOf { it.measure().firstOrNull()?.value ?: 0.0 }
                }

            logger.info("Health metrics - HTTP requests: {}", httpRequests)
        } catch (e: Exception) {
            logger.warn("Failed to collect health metrics", e)
        }
    }
}