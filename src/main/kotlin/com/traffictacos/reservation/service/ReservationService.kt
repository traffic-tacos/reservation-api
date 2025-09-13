package com.traffictacos.reservation.service

import com.traffictacos.reservation.domain.*
import com.traffictacos.reservation.dto.*
import com.traffictacos.reservation.grpc.InventoryGrpcClient
import com.traffictacos.reservation.repository.IdempotencyRepository
import com.traffictacos.reservation.repository.OrderRepository
import com.traffictacos.reservation.repository.OutboxRepository
import com.traffictacos.reservation.repository.ReservationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.*

@Service
class ReservationService(
    private val reservationRepository: ReservationRepository,
    private val orderRepository: OrderRepository,
    private val idempotencyRepository: IdempotencyRepository,
    private val outboxRepository: OutboxRepository,
    private val inventoryGrpcClient: InventoryGrpcClient,
    private val objectMapper: ObjectMapper,
    @Value("\${app.reservation.hold-duration-seconds}") private val holdDurationSeconds: Long
) {
    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    fun createReservation(request: CreateReservationRequest): Mono<CreateReservationResponse> {
        val idempotencyKey = extractIdempotencyKey() ?: return Mono.error(IllegalArgumentException("Idempotency-Key required"))
        val traceId = extractTraceId()

        logger.info("Creating reservation for event: {}, user: {}, seats: {}, idempotencyKey: {}",
            request.eventId, request.userId, request.seatIds, idempotencyKey)

        return checkIdempotency(idempotencyKey, request)
            .flatMap { existingResponse ->
                if (existingResponse != null) {
                    logger.info("Idempotent request detected, returning cached response for key: {}", idempotencyKey)
                    Mono.just(objectMapper.readValue(existingResponse, CreateReservationResponse::class.java))
                } else {
                    createNewReservation(request, idempotencyKey, traceId)
                }
            }
    }

    private fun checkIdempotency(idempotencyKey: String, request: CreateReservationRequest): Mono<String?> {
        return idempotencyRepository.findByKey(idempotencyKey)
            .map { record ->
                if (record != null) {
                    val requestHash = generateRequestHash(request)
                    if (record.requestHash == requestHash) {
                        record.responseSnapshot
                    } else {
                        throw IllegalArgumentException("Idempotency conflict: different request with same key")
                    }
                } else {
                    null
                }
            }
    }

    private fun createNewReservation(
        request: CreateReservationRequest,
        idempotencyKey: String,
        traceId: String?
    ): Mono<CreateReservationResponse> {
        // 1. Check inventory availability
        return inventoryGrpcClient.checkAvailability(request.eventId, request.seatIds, request.qty)
            .flatMap { availabilityResponse ->
                if (!availabilityResponse.available) {
                    logger.warn("Seats not available for event: {}, unavailable: {}",
                        request.eventId, availabilityResponse.unavailableSeatsList)
                    Mono.error(IllegalStateException("Seats not available: ${availabilityResponse.unavailableSeatsList}"))
                } else {
                    // 2. Create reservation entity
                    val holdExpiresAt = Instant.now().plusSeconds(holdDurationSeconds)
                    val reservation = Reservation(
                        eventId = request.eventId,
                        userId = request.userId,
                        qty = request.qty,
                        seatIds = request.seatIds,
                        holdExpiresAt = holdExpiresAt,
                        idempotencyKey = idempotencyKey
                    )

                    // 3. Save to database
                    reservationRepository.save(reservation)
                        .flatMap { savedReservation ->
                            // 4. Publish event to outbox
                            publishReservationCreatedEvent(savedReservation, traceId)
                                .map {
                                    CreateReservationResponse(
                                        reservationId = savedReservation.reservationId,
                                        holdExpiresAt = savedReservation.holdExpiresAt!!
                                    )
                                }
                        }
                        .flatMap { response ->
                            // 5. Store idempotency record
                            storeIdempotencyRecord(idempotencyKey, request, response)
                                .map { response }
                        }
                }
            }
    }

    fun confirmReservation(request: ConfirmReservationRequest): Mono<ConfirmReservationResponse> {
        val traceId = extractTraceId()
        logger.info("Confirming reservation: {}, paymentIntent: {}", request.reservationId, request.paymentIntentId)

        return reservationRepository.findById(request.reservationId)
            .flatMap { reservation ->
                if (reservation == null) {
                    Mono.error(IllegalArgumentException("Reservation not found: ${request.reservationId}"))
                } else if (!reservation.canBeConfirmed()) {
                    Mono.error(IllegalStateException("Reservation cannot be confirmed: ${reservation.status}"))
                } else {
                    // 1. Commit reservation via gRPC
                    inventoryGrpcClient.commitReservation(
                        reservation.reservationId,
                        reservation.eventId,
                        reservation.seatIds,
                        reservation.qty,
                        request.paymentIntentId
                    ).flatMap { commitResponse ->
                        if (!commitResponse.success) {
                            logger.error("Failed to commit reservation: {}", commitResponse.message)
                            Mono.error(RuntimeException("Failed to commit reservation: ${commitResponse.message}"))
                        } else {
                            // 2. Update reservation status
                            reservationRepository.updateStatus(
                                reservation.reservationId,
                                reservation.eventId,
                                ReservationStatus.CONFIRMED
                            ).flatMap { updatedReservation ->
                                // 3. Create order
                                val order = Order(
                                    reservationId = reservation.reservationId,
                                    eventId = reservation.eventId,
                                    userId = reservation.userId,
                                    seatIds = reservation.seatIds,
                                    totalAmount = 50000L * reservation.qty, // Mock pricing
                                    paymentIntentId = request.paymentIntentId
                                )

                                orderRepository.save(order)
                                    .flatMap { savedOrder ->
                                        // 4. Publish confirmation event
                                        publishReservationConfirmedEvent(updatedReservation!!, savedOrder, traceId)
                                            .map {
                                                ConfirmReservationResponse(
                                                    orderId = savedOrder.orderId,
                                                    status = OrderStatus.CONFIRMED
                                                )
                                            }
                                    }
                            }
                        }
                    }
                }
            }
    }

    fun cancelReservation(request: CancelReservationRequest): Mono<CancelReservationResponse> {
        val traceId = extractTraceId()
        logger.info("Cancelling reservation: {}", request.reservationId)

        return reservationRepository.findById(request.reservationId)
            .flatMap { reservation ->
                if (reservation == null) {
                    Mono.error(IllegalArgumentException("Reservation not found: ${request.reservationId}"))
                } else if (!reservation.canBeCancelled()) {
                    Mono.error(IllegalStateException("Reservation cannot be cancelled: ${reservation.status}"))
                } else {
                    // 1. Release hold via gRPC
                    inventoryGrpcClient.releaseHold(
                        reservation.reservationId,
                        reservation.eventId,
                        reservation.seatIds,
                        reservation.qty
                    ).flatMap { releaseResponse ->
                        if (!releaseResponse.success) {
                            logger.warn("Failed to release hold: {}", releaseResponse.message)
                            // Continue with cancellation even if gRPC fails
                        }

                        // 2. Update reservation status
                        reservationRepository.updateStatus(
                            reservation.reservationId,
                            reservation.eventId,
                            ReservationStatus.CANCELLED
                        ).flatMap { updatedReservation ->
                            // 3. Publish cancellation event
                            publishReservationCancelledEvent(updatedReservation!!, traceId)
                                .map {
                                    CancelReservationResponse(status = "CANCELLED")
                                }
                        }
                    }
                }
            }
    }

    fun getReservation(reservationId: String): Mono<ReservationResponse> {
        return reservationRepository.findById(reservationId)
            .map { reservation ->
                if (reservation == null) {
                    throw IllegalArgumentException("Reservation not found: $reservationId")
                }
                ReservationResponse(
                    reservationId = reservation.reservationId,
                    eventId = reservation.eventId,
                    userId = reservation.userId,
                    qty = reservation.qty,
                    seatIds = reservation.seatIds,
                    status = when (reservation.status) {
                        ReservationStatus.HOLD -> ReservationStatus.HOLD
                        ReservationStatus.CONFIRMED -> ReservationStatus.CONFIRMED
                        ReservationStatus.CANCELLED -> ReservationStatus.CANCELLED
                        ReservationStatus.EXPIRED -> ReservationStatus.EXPIRED
                    },
                    holdExpiresAt = reservation.holdExpiresAt,
                    createdAt = reservation.createdAt,
                    updatedAt = reservation.updatedAt
                )
            }
    }

    private fun publishReservationCreatedEvent(reservation: Reservation, traceId: String?): Mono<Unit> {
        val event = OutboxEvent(
            type = EventType.RESERVATION_CREATED,
            aggregateId = reservation.reservationId,
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "reservationId" to reservation.reservationId,
                    "eventId" to reservation.eventId,
                    "userId" to reservation.userId,
                    "qty" to reservation.qty,
                    "seatIds" to reservation.seatIds,
                    "holdExpiresAt" to reservation.holdExpiresAt
                )
            ),
            traceId = traceId
        )
        return outboxRepository.save(event).map { }
    }

    private fun publishReservationConfirmedEvent(reservation: Reservation, order: Order, traceId: String?): Mono<Unit> {
        val event = OutboxEvent(
            type = EventType.RESERVATION_CONFIRMED,
            aggregateId = reservation.reservationId,
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "reservationId" to reservation.reservationId,
                    "orderId" to order.orderId,
                    "eventId" to reservation.eventId,
                    "userId" to reservation.userId,
                    "totalAmount" to order.totalAmount
                )
            ),
            traceId = traceId
        )
        return outboxRepository.save(event).map { }
    }

    private fun publishReservationCancelledEvent(reservation: Reservation, traceId: String?): Mono<Unit> {
        val event = OutboxEvent(
            type = EventType.RESERVATION_CANCELLED,
            aggregateId = reservation.reservationId,
            payload = objectMapper.writeValueAsString(
                mapOf(
                    "reservationId" to reservation.reservationId,
                    "eventId" to reservation.eventId,
                    "userId" to reservation.userId,
                    "reason" to "user_cancelled"
                )
            ),
            traceId = traceId
        )
        return outboxRepository.save(event).map { }
    }

    private fun storeIdempotencyRecord(
        idempotencyKey: String,
        request: CreateReservationRequest,
        response: CreateReservationResponse
    ): Mono<Unit> {
        val ttl = Instant.now().plusSeconds(300) // 5 minutes
        val record = IdempotencyRecord(
            idempotencyKey = idempotencyKey,
            requestHash = generateRequestHash(request),
            responseSnapshot = objectMapper.writeValueAsString(response),
            ttl = ttl
        )
        return idempotencyRepository.save(record).map { }
    }

    private fun generateRequestHash(request: CreateReservationRequest): String {
        return UUID.nameUUIDFromBytes(
            "${request.eventId}:${request.userId}:${request.seatIds}:${request.qty}".toByteArray()
        ).toString()
    }

    private fun extractIdempotencyKey(): String? {
        // In a real implementation, this would be extracted from the current request context
        // For now, returning a mock value
        return UUID.randomUUID().toString()
    }

    private fun extractTraceId(): String? {
        // In a real implementation, this would be extracted from tracing context
        return UUID.randomUUID().toString()
    }
}

data class CancelReservationResponse(
    val status: String
)
