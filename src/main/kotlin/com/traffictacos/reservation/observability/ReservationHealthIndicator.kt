package com.traffictacos.reservation.observability

import com.traffictacos.reservation.grpc.InventoryGrpcClient
import com.traffictacos.reservation.repository.ReservationRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.actuator.health.Health
import org.springframework.boot.actuator.health.HealthIndicator
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest
import java.time.Duration
import java.time.Instant

@Component
class ReservationHealthIndicator(
    private val dynamoDbAsyncClient: DynamoDbAsyncClient,
    private val inventoryGrpcClient: InventoryGrpcClient,
    private val meterRegistry: MeterRegistry
) : HealthIndicator {

    private var lastHealthCheck: Instant = Instant.EPOCH
    private var cachedHealth: Health = Health.unknown().build()

    override fun health(): Health {
        // Cache health checks for 30 seconds to avoid overwhelming dependencies
        val now = Instant.now()
        if (Duration.between(lastHealthCheck, now).seconds < 30) {
            return cachedHealth
        }

        return try {
            val healthBuilder = Health.up()
            var allHealthy = true

            // Check DynamoDB connectivity
            val dynamoHealth = checkDynamoDbHealth()
            healthBuilder.withDetail("dynamodb", dynamoHealth)
            if (dynamoHealth["status"] != "UP") {
                allHealthy = false
            }

            // Check inventory service connectivity
            val inventoryHealth = checkInventoryServiceHealth()
            healthBuilder.withDetail("inventory", inventoryHealth)
            if (inventoryHealth["status"] != "UP") {
                allHealthy = false
            }

            // Add system metrics
            val systemHealth = getSystemHealthDetails()
            healthBuilder.withDetail("system", systemHealth)

            // Add business metrics
            val businessHealth = getBusinessHealthDetails()
            healthBuilder.withDetail("business", businessHealth)

            cachedHealth = if (allHealthy) healthBuilder.build() else Health.down().withDetails(healthBuilder.build().details).build()
            lastHealthCheck = now
            
            cachedHealth
        } catch (e: Exception) {
            val errorHealth = Health.down()
                .withDetail("error", e.message)
                .withDetail("timestamp", Instant.now().toString())
                .build()
            cachedHealth = errorHealth
            lastHealthCheck = now
            errorHealth
        }
    }

    private fun checkDynamoDbHealth(): Map<String, Any> {
        return try {
            val request = DescribeTableRequest.builder()
                .tableName("reservations") // Use actual table name
                .build()

            val startTime = System.currentTimeMillis()
            dynamoDbAsyncClient.describeTable(request).get(Duration.ofSeconds(5))
            val responseTime = System.currentTimeMillis() - startTime

            mapOf(
                "status" to "UP",
                "responseTime" to "${responseTime}ms",
                "timestamp" to Instant.now().toString()
            )
        } catch (e: Exception) {
            meterRegistry.counter("health.dynamodb.failures").increment()
            mapOf(
                "status" to "DOWN",
                "error" to (e.message ?: "Unknown error"),
                "timestamp" to Instant.now().toString()
            )
        }
    }

    private fun checkInventoryServiceHealth(): Map<String, Any> {
        return try {
            val startTime = System.currentTimeMillis()
            
            // Try a simple availability check with test data
            inventoryGrpcClient.checkAvailability("health-check", listOf("test"), 1)
                .timeout(Duration.ofSeconds(2))
                .block()
                
            val responseTime = System.currentTimeMillis() - startTime

            mapOf(
                "status" to "UP",
                "responseTime" to "${responseTime}ms",
                "timestamp" to Instant.now().toString()
            )
        } catch (e: Exception) {
            meterRegistry.counter("health.inventory.failures").increment()
            mapOf(
                "status" to "DOWN",
                "error" to (e.message ?: "Unknown error"),
                "timestamp" to Instant.now().toString()
            )
        }
    }

    private fun getSystemHealthDetails(): Map<String, Any> {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()

        return mapOf(
            "memory" to mapOf(
                "used" to "${usedMemory / 1024 / 1024}MB",
                "free" to "${freeMemory / 1024 / 1024}MB",
                "total" to "${totalMemory / 1024 / 1024}MB",
                "max" to "${maxMemory / 1024 / 1024}MB",
                "utilization" to "${"%.2f".format((usedMemory.toDouble() / maxMemory) * 100)}%"
            ),
            "threads" to mapOf(
                "active" to Thread.activeCount(),
                "daemon" to Thread.getAllStackTraces().keys.count { it.isDaemon },
                "peak" to Runtime.getRuntime().availableProcessors()
            ),
            "uptime" to Duration.ofMillis(
                java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
            ).toString()
        )
    }

    private fun getBusinessHealthDetails(): Map<String, Any> {
        return try {
            // Get metrics from MeterRegistry
            val httpRequestsTotal = meterRegistry.find("http.server.requests")
                .counter()?.count() ?: 0.0

            val httpErrors = meterRegistry.find("http.server.requests")
                .tag("status", "500")
                .counter()?.count() ?: 0.0

            val reservationMetrics = mapOf(
                "reservations_created" to (meterRegistry.find("api_requests_total")
                    .tag("endpoint", "create_reservation")
                    .counter()?.count() ?: 0.0),
                "reservations_confirmed" to (meterRegistry.find("api_requests_total")
                    .tag("endpoint", "confirm_reservation")
                    .counter()?.count() ?: 0.0),
                "reservations_cancelled" to (meterRegistry.find("api_requests_total")
                    .tag("endpoint", "cancel_reservation")
                    .counter()?.count() ?: 0.0)
            )

            val errorRate = if (httpRequestsTotal > 0) {
                (httpErrors / httpRequestsTotal) * 100
            } else 0.0

            mapOf(
                "http" to mapOf(
                    "total_requests" to httpRequestsTotal,
                    "error_rate" to "${"%.2f".format(errorRate)}%"
                ),
                "reservations" to reservationMetrics,
                "grpc" to mapOf(
                    "calls_total" to (meterRegistry.find("grpc_calls_total")
                        .counter()?.count() ?: 0.0),
                    "errors_total" to (meterRegistry.find("grpc_errors_total")
                        .counter()?.count() ?: 0.0)
                ),
                "events" to mapOf(
                    "published_total" to (meterRegistry.find("events_published_total")
                        .counter()?.count() ?: 0.0),
                    "failed_total" to (meterRegistry.find("events_failed_total")
                        .counter()?.count() ?: 0.0)
                )
            )
        } catch (e: Exception) {
            mapOf(
                "error" to "Failed to collect business metrics: ${e.message}"
            )
        }
    }
}