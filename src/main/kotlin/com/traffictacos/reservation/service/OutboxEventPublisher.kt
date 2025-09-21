package com.traffictacos.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.traffictacos.reservation.domain.*
import com.traffictacos.reservation.repository.OutboxRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*

@Service
class OutboxEventPublisher(
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(OutboxEventPublisher::class.java)

    suspend fun publishReservationCreated(reservation: Reservation) {
        val eventPayload = ReservationCreatedEvent(
            reservationId = reservation.reservationId,
            eventId = reservation.eventId,
            userId = reservation.userId,
            quantity = reservation.quantity,
            seatIds = reservation.seatIds,
            holdExpiresAt = reservation.holdExpiresAt!!,
            timestamp = Instant.now()
        )

        publishEvent("reservation.created", eventPayload)
    }

    suspend fun publishReservationConfirmed(reservation: Reservation, order: Order) {
        val eventPayload = ReservationConfirmedEvent(
            reservationId = reservation.reservationId,
            orderId = order.orderId,
            eventId = reservation.eventId,
            userId = reservation.userId,
            amount = order.amount,
            paymentIntentId = order.paymentIntentId,
            timestamp = Instant.now()
        )

        publishEvent("reservation.confirmed", eventPayload)
    }

    suspend fun publishReservationCancelled(reservation: Reservation) {
        val eventPayload = ReservationCancelledEvent(
            reservationId = reservation.reservationId,
            eventId = reservation.eventId,
            userId = reservation.userId,
            seatIds = reservation.seatIds,
            timestamp = Instant.now()
        )

        publishEvent("reservation.cancelled", eventPayload)
    }

    suspend fun publishReservationExpired(reservation: Reservation) {
        val eventPayload = ReservationExpiredEvent(
            reservationId = reservation.reservationId,
            eventId = reservation.eventId,
            userId = reservation.userId,
            seatIds = reservation.seatIds,
            timestamp = Instant.now()
        )

        publishEvent("reservation.expired", eventPayload)
    }

    private suspend fun publishEvent(eventType: String, payload: Any) {
        try {
            val outboxEvent = OutboxEvent(
                outboxId = UUID.randomUUID().toString(),
                eventType = eventType,
                payload = objectMapper.writeValueAsString(payload),
                status = OutboxStatus.PENDING
            )

            outboxRepository.saveAsync(outboxEvent)

            logger.info("Published event: {} with ID: {}", eventType, outboxEvent.outboxId)

            // In a real implementation, you would have a separate process/worker
            // that reads from the outbox table and publishes to EventBridge
            // For now, we'll just log that the event was saved to the outbox

        } catch (e: Exception) {
            logger.error("Failed to publish event: {}", eventType, e)
            // In a real implementation, you might want to handle this differently
            throw e
        }
    }
}

// Event payload data classes
data class ReservationCreatedEvent(
    val reservationId: String,
    val eventId: String,
    val userId: String,
    val quantity: Int,
    val seatIds: List<String>,
    val holdExpiresAt: Instant,
    val timestamp: Instant
)

data class ReservationConfirmedEvent(
    val reservationId: String,
    val orderId: String,
    val eventId: String,
    val userId: String,
    val amount: java.math.BigDecimal,
    val paymentIntentId: String,
    val timestamp: Instant
)

data class ReservationCancelledEvent(
    val reservationId: String,
    val eventId: String,
    val userId: String,
    val seatIds: List<String>,
    val timestamp: Instant
)

data class ReservationExpiredEvent(
    val reservationId: String,
    val eventId: String,
    val userId: String,
    val seatIds: List<String>,
    val timestamp: Instant
)