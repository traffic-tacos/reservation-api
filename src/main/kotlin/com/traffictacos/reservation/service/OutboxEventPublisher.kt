package com.traffictacos.reservation.service

import com.traffictacos.reservation.domain.OutboxEvent
import com.traffictacos.reservation.repository.OutboxRepository
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

@Service
class OutboxEventPublisher(
    private val outboxRepository: OutboxRepository,
    private val eventBridgeClient: EventBridgeClient,
    private val meterRegistry: MeterRegistry,
    @Value("\${aws.eventbridge.bus-name:ticket-reservation-events}") private val eventBusName: String
) {
    companion object {
        private const val EVENT_SOURCE = "reservation.service"
        private const val EVENT_PUBLISHED_COUNTER = "events_published_total"
        private const val EVENT_FAILED_COUNTER = "events_failed_total"
    }

    fun publishEvent(event: OutboxEvent): Mono<Void> {
        return outboxRepository.save(event)
            .flatMap { savedEvent ->
                publishToEventBridge(savedEvent)
                    .then(markEventAsCompleted(savedEvent))
                    .doOnSuccess {
                        meterRegistry.counter(EVENT_PUBLISHED_COUNTER, "event_type", event.type.name).increment()
                        logger.info { "Event published successfully: ${event.outboxId}" }
                    }
                    .doOnError { error ->
                        meterRegistry.counter(EVENT_FAILED_COUNTER, "event_type", event.type.name).increment()
                        logger.error(error) { "Failed to publish event: ${event.outboxId}" }
                        markEventAsFailed(savedEvent, error.message ?: "Unknown error")
                            .subscribe()
                    }
            }
    }

    fun publishPendingEvents(): Mono<Void> {
        return outboxRepository.findPendingEvents()
            .flatMap { event ->
                publishToEventBridge(event)
                    .then(markEventAsCompleted(event))
                    .onErrorResume { error ->
                        logger.error(error) { "Failed to publish pending event: ${event.outboxId}" }
                        markEventAsFailed(event, error.message ?: "Unknown error")
                    }
            }
            .then()
    }

    fun retryFailedEvents(): Mono<Void> {
        return outboxRepository.findRetryableEvents()
            .filter { it.canRetry() }
            .flatMap { event ->
                val processingEvent = event.markAsProcessing()
                outboxRepository.save(processingEvent)
                    .then(publishToEventBridge(processingEvent))
                    .then(markEventAsCompleted(processingEvent))
                    .onErrorResume { error ->
                        val nextRetryAt = calculateNextRetryTime(processingEvent.attempts)
                        markEventAsFailed(processingEvent, error.message ?: "Unknown error", nextRetryAt)
                    }
            }
            .then()
    }

    private fun publishToEventBridge(event: OutboxEvent): Mono<Void> {
        return Mono.fromCallable {
            val entry = PutEventsRequestEntry.builder()
                .source(EVENT_SOURCE)
                .detailType(mapEventTypeToDetailType(event.type))
                .detail(event.payload)
                .eventBusName(eventBusName)
                .time(Instant.now())
                .build()

            val request = PutEventsRequest.builder()
                .entries(entry)
                .build()

            val response = eventBridgeClient.putEvents(request)
            
            if (response.failedEntryCount() > 0) {
                val failedEntry = response.entries().firstOrNull { it.errorCode() != null }
                throw RuntimeException("Failed to publish event: ${failedEntry?.errorCode()} - ${failedEntry?.errorMessage()}")
            }
            
            logger.debug { "Event published to EventBridge: ${event.outboxId}" }
        }.subscribeOn(Schedulers.boundedElastic())
            .then()
    }

    private fun markEventAsCompleted(event: OutboxEvent): Mono<Void> {
        val completedEvent = event.markAsCompleted()
        return outboxRepository.save(completedEvent).then()
    }

    private fun markEventAsFailed(event: OutboxEvent, errorMessage: String, nextRetryAt: Instant? = null): Mono<Void> {
        val failedEvent = event.markAsFailed(errorMessage, nextRetryAt)
        return outboxRepository.save(failedEvent).then()
    }

    private fun calculateNextRetryTime(attempts: Int): Instant? {
        return if (attempts < OutboxEvent.MAX_RETRY_ATTEMPTS) {
            // Exponential backoff: 1min, 2min, 4min, 8min
            val delayMinutes = Math.pow(2.0, attempts.toDouble()).toLong()
            Instant.now().plusSeconds(delayMinutes * 60)
        } else {
            null // No more retries
        }
    }

    private fun mapEventTypeToDetailType(eventType: com.traffictacos.reservation.domain.EventType): String {
        return when (eventType) {
            com.traffictacos.reservation.domain.EventType.RESERVATION_CREATED -> "Reservation Created"
            com.traffictacos.reservation.domain.EventType.RESERVATION_CONFIRMED -> "Reservation Status Changed"
            com.traffictacos.reservation.domain.EventType.RESERVATION_CANCELLED -> "Reservation Status Changed"
            com.traffictacos.reservation.domain.EventType.RESERVATION_EXPIRED -> "Reservation Status Changed"
        }
    }
}