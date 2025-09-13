package com.traffictacos.reservation.service

import com.traffictacos.reservation.domain.EventType
import com.traffictacos.reservation.domain.OutboxEvent
import com.traffictacos.reservation.domain.Reservation
import com.traffictacos.reservation.domain.ReservationStatus
import com.traffictacos.reservation.grpc.InventoryGrpcClient
import com.traffictacos.reservation.repository.OutboxRepository
import com.traffictacos.reservation.repository.ReservationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

@Service
class ReservationExpiryService(
    private val reservationRepository: ReservationRepository,
    private val inventoryGrpcClient: InventoryGrpcClient,
    private val outboxRepository: OutboxRepository,
    private val eventBridgeClient: EventBridgeClient,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ReservationExpiryService::class.java)

    fun scheduleReservationExpiry(reservation: Reservation): Mono<Unit> {
        return Mono.fromCallable {
            if (reservation.holdExpiresAt == null) {
                logger.warn("Cannot schedule expiry for reservation without hold expiry: {}", reservation.reservationId)
                return@fromCallable
            }

            val ruleName = "reservation-expiry-${reservation.reservationId}"
            val targetId = "reservation-expiry-target-${reservation.reservationId}"

            try {
                // Create EventBridge rule
                val putRuleRequest = PutRuleRequest.builder()
                    .name(ruleName)
                    .scheduleExpression("at(${formatScheduleTime(reservation.holdExpiresAt!!)})")
                    .state(RuleState.ENABLED)
                    .description("Reservation expiry for ${reservation.reservationId}")
                    .build()

                eventBridgeClient.putRule(putRuleRequest)
                logger.debug("Created EventBridge rule for reservation: {}", reservation.reservationId)

                // Create target
                val target = Target.builder()
                    .id(targetId)
                    .arn("arn:aws:events:ap-northeast-2:123456789012:event-bus/default") // Mock ARN for development
                    .roleArn("arn:aws:iam::123456789012:role/reservation-expiry-role") // Mock role for development
                    .input(objectMapper.writeValueAsString(
                        mapOf(
                            "reservationId" to reservation.reservationId,
                            "eventId" to reservation.eventId,
                            "action" to "expire"
                        )
                    ))
                    .build()

                val putTargetsRequest = PutTargetsRequest.builder()
                    .rule(ruleName)
                    .targets(target)
                    .build()

                eventBridgeClient.putTargets(putTargetsRequest)
                logger.debug("Created EventBridge target for reservation: {}", reservation.reservationId)

            } catch (e: Exception) {
                logger.error("Failed to schedule reservation expiry: {}", reservation.reservationId, e)
                // Continue without failing - expiry will be handled by periodic cleanup
            }
        }
    }

    @Scheduled(fixedRate = 30000) // Run every 30 seconds
    fun processExpiredReservations() {
        logger.debug("Processing expired reservations")

        val now = Instant.now()

        reservationRepository.findExpiredReservations(now)
            .flatMap { reservations ->
                if (reservations.isEmpty()) {
                    logger.debug("No expired reservations found")
                    Mono.just(emptyList())
                } else {
                    logger.info("Found {} expired reservations", reservations.size)

                    val processingMonos = reservations.map { reservation ->
                        processExpiredReservation(reservation)
                    }

                    Mono.zip(processingMonos) { results ->
                        results.map { it as Int }.sum()
                    }
                }
            }
            .doOnError { error ->
                logger.error("Error processing expired reservations", error)
            }
            .onErrorResume { Mono.just(0) }
            .block() // Synchronous execution for scheduled method
    }

    private fun processExpiredReservation(reservation: Reservation): Mono<Int> {
        logger.info("Processing expired reservation: {}", reservation.reservationId)

        return reservationRepository.updateStatus(
            reservation.reservationId,
            reservation.eventId,
            ReservationStatus.EXPIRED
        ).flatMap { updatedReservation ->
            if (updatedReservation == null) {
                logger.warn("Failed to update reservation status for: {}", reservation.reservationId)
                Mono.just(0)
            } else {
                // Release hold via gRPC
                inventoryGrpcClient.releaseHold(
                    reservation.reservationId,
                    reservation.eventId,
                    reservation.seatIds,
                    reservation.qty
                ).flatMap { releaseResponse ->
                    if (!releaseResponse.success) {
                        logger.warn("Failed to release hold for expired reservation: {}, message: {}",
                            reservation.reservationId, releaseResponse.message)
                        // Continue processing even if gRPC fails
                    }

                    // Publish expiry event
                    publishReservationExpiredEvent(updatedReservation)
                        .map { 1 } // Return 1 for successful processing
                }
            }
        }.onErrorResume { error ->
            logger.error("Error processing expired reservation: {}", reservation.reservationId, error)
            Mono.just(0) // Return 0 for failed processing
        }
    }

    private fun publishReservationExpiredEvent(reservation: Reservation): Mono<Unit> {
        val event = OutboxEvent(
            type = EventType.RESERVATION_EXPIRED,
            aggregateId = reservation.reservationId,
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "reservationId" to reservation.reservationId,
                    "eventId" to reservation.eventId,
                    "userId" to reservation.userId,
                    "expiredAt" to Instant.now(),
                    "reason" to "hold_expired"
                )
            ),
            traceId = UUID.randomUUID().toString()
        )
        return outboxRepository.save(event).map { }
    }

    private fun formatScheduleTime(instant: Instant): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC)
            .format(instant)
    }

    fun cleanupExpiredEventBridgeRules() {
        // In production, you might want to periodically clean up old EventBridge rules
        // This is a simplified implementation
        logger.debug("Cleaning up expired EventBridge rules (not implemented)")
    }

    // Handle EventBridge events (would be called by event handler)
    fun handleReservationExpiryEvent(payload: String): Mono<Unit> {
        return Mono.fromCallable {
            val eventData = objectMapper.readValue(payload, Map::class.java)
            val reservationId = eventData["reservationId"] as? String
            val eventId = eventData["eventId"] as? String

            if (reservationId != null && eventId != null) {
                logger.info("Processing EventBridge expiry event for reservation: {}", reservationId)

                reservationRepository.findByIdAndEventId(reservationId, eventId)
                    .flatMap { reservation ->
                        if (reservation != null && reservation.canBeCancelled()) {
                            processExpiredReservation(reservation)
                        } else {
                            logger.debug("Reservation not found or cannot be expired: {}", reservationId)
                            Mono.just(0)
                        }
                    }
                    .block() // Synchronous for event handling
            }
        }
    }
}
