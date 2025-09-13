package com.traffictacos.reservation.service

import com.traffictacos.reservation.domain.OutboxEvent
import com.traffictacos.reservation.repository.OutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import java.time.Instant

@Service
class OutboxEventPublisher(
    private val outboxRepository: OutboxRepository,
    private val eventBridgeClient: EventBridgeClient,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(OutboxEventPublisher::class.java)

    @Scheduled(fixedRate = 10000) // Run every 10 seconds
    fun publishPendingEvents() {
        logger.debug("Publishing pending outbox events")

        outboxRepository.findPendingEvents(limit = 10)
            .flatMap { events ->
                if (events.isEmpty()) {
                    logger.debug("No pending events to publish")
                    Mono.just(emptyList())
                } else {
                    logger.info("Found {} pending events to publish", events.size)

                    val publishMonos = events.map { event ->
                        publishEvent(event)
                    }

                    Mono.zip(publishMonos) { results ->
                        results.map { it as Int }.sum()
                    }
                }
            }
            .doOnError { error ->
                logger.error("Error publishing outbox events", error)
            }
            .onErrorResume { Mono.just(0) }
            .block() // Synchronous execution for scheduled method
    }

    private fun publishEvent(event: OutboxEvent): Mono<Int> {
        return Mono.fromCallable {
            try {
                logger.debug("Publishing event: {} for aggregate: {}", event.type, event.aggregateId)

                // Mark as processing
                val processingEvent = event.markAsProcessing()
                outboxRepository.save(processingEvent).block()

                // Create EventBridge event
                val eventEntry = PutEventsRequestEntry.builder()
                    .source("reservation-api")
                    .detailType(event.type.name)
                    .detail(event.payload)
                    .eventBusName("default")
                    .time(Instant.now())
                    .applyMutation {
                        if (event.traceId != null) {
                            it.traceHeader(event.traceId)
                        }
                    }
                    .build()

                val putEventsRequest = PutEventsRequest.builder()
                    .entries(eventEntry)
                    .build()

                // Publish to EventBridge
                val response = eventBridgeClient.putEvents(putEventsRequest)

                if (response.failedEntryCount() > 0) {
                    logger.error("Failed to publish event: {}, failed entries: {}",
                        event.outboxId, response.failedEntryCount())

                    // Mark as failed with retry
                    val nextRetryAt = calculateNextRetryAt(event.attempts)
                    val failedEvent = event.markAsFailed(
                        "EventBridge publish failed",
                        nextRetryAt
                    )
                    outboxRepository.save(failedEvent).block()

                    0 // Failed
                } else {
                    logger.debug("Successfully published event: {}", event.outboxId)

                    // Mark as completed and delete
                    outboxRepository.deleteById(event.outboxId).block()

                    1 // Success
                }

            } catch (e: Exception) {
                logger.error("Error publishing event: {}", event.outboxId, e)

                // Mark as failed with retry
                val nextRetryAt = calculateNextRetryAt(event.attempts)
                val failedEvent = event.markAsFailed(e.message ?: "Unknown error", nextRetryAt)
                outboxRepository.save(failedEvent).block()

                0 // Failed
            }
        }
    }

    private fun calculateNextRetryAt(attempts: Int): Instant {
        // Exponential backoff: 30s, 1m, 2m, 4m, 8m
        val delaySeconds = 30L * (1L shl attempts.coerceAtMost(4))
        return Instant.now().plusSeconds(delaySeconds)
    }

    // Manual trigger for testing or immediate publishing
    fun publishEventImmediately(outboxId: String): Mono<Boolean> {
        return outboxRepository.findById(outboxId)
            .flatMap { event ->
                if (event == null) {
                    logger.warn("Outbox event not found: {}", outboxId)
                    Mono.just(false)
                } else if (event.status != OutboxEvent.OutboxStatus.PENDING) {
                    logger.warn("Event not in pending status: {}", outboxId)
                    Mono.just(false)
                } else {
                    publishEvent(event).map { it > 0 }
                }
            }
    }

    // Cleanup old completed events (optional)
    @Scheduled(fixedRate = 3600000) // Run every hour
    fun cleanupOldEvents() {
        logger.debug("Cleaning up old completed outbox events")

        // This would require additional query capabilities
        // For now, just log that cleanup would happen
        logger.debug("Old event cleanup completed (not implemented)")
    }

    // Health check method
    fun getPendingEventCount(): Mono<Int> {
        return outboxRepository.findPendingEvents(limit = 1000)
            .map { it.size }
    }
}
