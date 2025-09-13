package com.traffictacos.reservation.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import javax.annotation.PostConstruct

@Configuration
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
                    meters.sumOf { meter ->
                        meter.measure().firstOrNull()?.value?.toLong() ?: 0L
                    }
                }

            if (httpRequests.isNotEmpty()) {
                logger.info("HTTP Request Metrics: {}", httpRequests)
            }

            // Log reservation status counts
            val reservationStatuses = meterRegistry.find("reservation.status.total")
                .meters()
                .groupBy { it.id.tags.firstOrNull { tag -> tag.key == "status" }?.value }
                .mapValues { (_, meters) ->
                    meters.sumOf { meter ->
                        meter.measure().firstOrNull()?.value?.toLong() ?: 0L
                    }
                }

            if (reservationStatuses.isNotEmpty()) {
                logger.info("Reservation Status Metrics: {}", reservationStatuses)
            }

        } catch (e: Exception) {
            logger.warn("Error logging health metrics", e)
        }
    }
}
