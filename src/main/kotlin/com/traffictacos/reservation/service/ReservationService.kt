package com.traffictacos.reservation.service

import com.traffictacos.reservation.domain.*
import com.traffictacos.reservation.dto.*
import com.traffictacos.reservation.repository.*
import com.traffictacos.reservation.grpc.InventoryGrpcClient
import com.traffictacos.reservation.exception.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import mu.KotlinLogging
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.time.Instant
import java.time.Duration
import java.util.*

private val logger = KotlinLogging.logger {}

@Service
class ReservationService(
    private val reservationRepository: ReservationRepository,
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRepository,
    private val idempotencyService: IdempotencyService,
    private val inventoryGrpcClient: InventoryGrpcClient,
    private val eventPublisher: OutboxEventPublisher,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private val HOLD_DURATION = Duration.ofSeconds(60)
        private const val RESERVATION_CREATED_COUNTER = "reservation_created_total"
        private const val RESERVATION_CONFIRMED_COUNTER = "reservation_confirmed_total"
        private const val RESERVATION_CANCELLED_COUNTER = "reservation_cancelled_total"
        private const val RESERVATION_EXPIRED_COUNTER = "reservation_expired_total"
    }

    fun createReservation(
        request: CreateReservationRequest,
        userId: String,
        idempotencyKey: String
    ): Mono<CreateReservationResponse> {
        val timer = Timer.start(meterRegistry)
        
        return idempotencyService.executeIdempotent(idempotencyKey) {
            logger.info { "Creating reservation for user=$userId, event=${request.eventId}, seats=${request.seatIds}" }
            
            // Step 1: Check inventory availability via gRPC
            inventoryGrpcClient.checkAvailability(request.eventId, request.seatIds, request.quantity)
                .flatMap { availabilityResponse ->
                    if (!availabilityResponse.available) {
                        logger.warn { "Inventory unavailable for event=${request.eventId}, unavailable_seats=${availabilityResponse.unavailableSeats}" }
                        Mono.error(InventoryConflictException("Seats not available: ${availabilityResponse.unavailableSeats}"))
                    } else {
                        // Step 2: Create reservation with hold
                        val reservation = Reservation(
                            eventId = request.eventId,
                            userId = userId,
                            qty = request.quantity,
                            seatIds = request.seatIds,
                            status = ReservationStatus.HOLD,
                            holdExpiresAt = Instant.now().plus(HOLD_DURATION),
                            idempotencyKey = idempotencyKey
                        )
                        
                        reservationRepository.save(reservation)
                            .flatMap { savedReservation ->
                                // Step 3: Publish reservation created event
                                val event = OutboxEvent(
                                    type = EventType.RESERVATION_CREATED,
                                    aggregateId = savedReservation.reservationId,
                                    payload = createEventPayload(savedReservation)
                                )
                                
                                eventPublisher.publishEvent(event)
                                    .then(Mono.just(savedReservation))
                            }
                            .doOnSuccess {
                                meterRegistry.counter(RESERVATION_CREATED_COUNTER, "event_id", request.eventId).increment()
                                timer.stop(Timer.Sample.builder(meterRegistry).register("reservation_create_duration"))
                                logger.info { "Reservation created successfully: ${it.reservationId}" }
                            }
                            .map { reservation ->
                                CreateReservationResponse(
                                    reservationId = reservation.reservationId,
                                    holdExpiresAt = reservation.holdExpiresAt!!
                                )
                            }
                    }
                }
        }.cast(CreateReservationResponse::class.java)
    }

    fun getReservation(reservationId: String): Mono<ReservationResponse> {
        return reservationRepository.findById(reservationId)
            .switchIfEmpty { 
                logger.warn { "Reservation not found: $reservationId" }
                Mono.error(ReservationNotFoundException("Reservation not found: $reservationId")) 
            }
            .map { reservation ->
                ReservationResponse(
                    reservationId = reservation.reservationId,
                    eventId = reservation.eventId,
                    userId = reservation.userId,
                    quantity = reservation.qty,
                    seatIds = reservation.seatIds,
                    status = reservation.status.name,
                    holdExpiresAt = reservation.holdExpiresAt,
                    createdAt = reservation.createdAt,
                    updatedAt = reservation.updatedAt
                )
            }
    }

    fun confirmReservation(
        reservationId: String,
        request: ConfirmReservationRequest,
        userId: String
    ): Mono<ConfirmReservationResponse> {
        val timer = Timer.start(meterRegistry)
        
        return reservationRepository.findById(reservationId)
            .switchIfEmpty { 
                Mono.error(ReservationNotFoundException("Reservation not found: $reservationId")) 
            }
            .flatMap { reservation ->
                // Validate reservation can be confirmed
                if (reservation.userId != userId) {
                    Mono.error(ForbiddenException("Reservation belongs to different user"))
                } else if (!reservation.canBeConfirmed()) {
                    if (reservation.isExpired()) {
                        Mono.error(ReservationExpiredException("Reservation has expired"))
                    } else {
                        Mono.error(InvalidReservationStateException("Reservation cannot be confirmed in current state: ${reservation.status}"))
                    }
                } else {
                    // Step 1: Commit reservation in inventory via gRPC
                    inventoryGrpcClient.commitReservation(
                        reservationId = reservation.reservationId,
                        eventId = reservation.eventId,
                        seatIds = reservation.seatIds,
                        quantity = reservation.qty,
                        paymentIntentId = request.paymentIntentId
                    ).flatMap { commitResponse ->
                        // Step 2: Create order and update reservation
                        val order = Order(
                            reservationId = reservation.reservationId,
                            eventId = reservation.eventId,
                            userId = reservation.userId,
                            seatIds = reservation.seatIds,
                            totalAmount = calculateTotalAmount(reservation.qty), // TODO: Get from pricing service
                            paymentIntentId = request.paymentIntentId
                        )
                        
                        val confirmedReservation = reservation.confirm()
                        
                        // Step 3: Save order and update reservation atomically
                        Mono.zip(
                            orderRepository.save(order),
                            reservationRepository.save(confirmedReservation)
                        ).flatMap { (savedOrder, savedReservation) ->
                            // Step 4: Publish confirmation event
                            val event = OutboxEvent(
                                type = EventType.RESERVATION_CONFIRMED,
                                aggregateId = savedReservation.reservationId,
                                payload = createEventPayload(savedReservation, savedOrder)
                            )
                            
                            eventPublisher.publishEvent(event)
                                .then(Mono.just(Pair(savedOrder, savedReservation)))
                        }.doOnSuccess { (order, _) ->
                            meterRegistry.counter(RESERVATION_CONFIRMED_COUNTER, "event_id", reservation.eventId).increment()
                            timer.stop(Timer.Sample.builder(meterRegistry).register("reservation_confirm_duration"))
                            logger.info { "Reservation confirmed successfully: $reservationId, order: ${order.orderId}" }
                        }.map { (order, _) ->
                            ConfirmReservationResponse(
                                orderId = order.orderId,
                                status = "CONFIRMED"
                            )
                        }
                    }
                }
            }
    }

    fun cancelReservation(reservationId: String, userId: String): Mono<CancelReservationResponse> {
        return reservationRepository.findById(reservationId)
            .switchIfEmpty { 
                Mono.error(ReservationNotFoundException("Reservation not found: $reservationId")) 
            }
            .flatMap { reservation ->
                // Validate reservation can be cancelled
                if (reservation.userId != userId) {
                    Mono.error(ForbiddenException("Reservation belongs to different user"))
                } else if (!reservation.canBeCancelled()) {
                    Mono.error(InvalidReservationStateException("Reservation cannot be cancelled in current state: ${reservation.status}"))
                } else {
                    // Step 1: Release hold in inventory via gRPC
                    inventoryGrpcClient.releaseHold(
                        reservationId = reservation.reservationId,
                        eventId = reservation.eventId,
                        seatIds = reservation.seatIds,
                        quantity = reservation.qty
                    ).flatMap {
                        // Step 2: Update reservation status
                        val cancelledReservation = reservation.cancel()
                        
                        reservationRepository.save(cancelledReservation)
                            .flatMap { savedReservation ->
                                // Step 3: Publish cancellation event
                                val event = OutboxEvent(
                                    type = EventType.RESERVATION_CANCELLED,
                                    aggregateId = savedReservation.reservationId,
                                    payload = createEventPayload(savedReservation)
                                )
                                
                                eventPublisher.publishEvent(event)
                                    .then(Mono.just(savedReservation))
                            }
                    }.doOnSuccess {
                        meterRegistry.counter(RESERVATION_CANCELLED_COUNTER, "event_id", reservation.eventId).increment()
                        logger.info { "Reservation cancelled successfully: $reservationId" }
                    }.map {
                        CancelReservationResponse(status = "CANCELLED")
                    }
                }
            }
    }

    fun expireReservation(reservationId: String): Mono<Void> {
        return reservationRepository.findById(reservationId)
            .filter { it.status == ReservationStatus.HOLD && it.isExpired() }
            .flatMap { reservation ->
                // Step 1: Release hold in inventory
                inventoryGrpcClient.releaseHold(
                    reservationId = reservation.reservationId,
                    eventId = reservation.eventId,
                    seatIds = reservation.seatIds,
                    quantity = reservation.qty
                ).flatMap {
                    // Step 2: Update reservation status
                    val expiredReservation = reservation.expire()
                    
                    reservationRepository.save(expiredReservation)
                        .flatMap { savedReservation ->
                            // Step 3: Publish expiry event
                            val event = OutboxEvent(
                                type = EventType.RESERVATION_EXPIRED,
                                aggregateId = savedReservation.reservationId,
                                payload = createEventPayload(savedReservation)
                            )
                            
                            eventPublisher.publishEvent(event)
                        }
                }.doOnSuccess {
                    meterRegistry.counter(RESERVATION_EXPIRED_COUNTER, "event_id", reservation.eventId).increment()
                    logger.info { "Reservation expired successfully: $reservationId" }
                }
            }.then()
    }

    private fun createEventPayload(reservation: Reservation, order: Order? = null): String {
        val payload = mutableMapOf(
            "reservation_id" to reservation.reservationId,
            "event_id" to reservation.eventId,
            "user_id" to reservation.userId,
            "seat_ids" to reservation.seatIds,
            "quantity" to reservation.qty,
            "status" to reservation.status.name,
            "created_at" to reservation.createdAt.toString(),
            "updated_at" to reservation.updatedAt.toString()
        )
        
        order?.let {
            payload["order_id"] = it.orderId
            payload["total_amount"] = it.totalAmount
            payload["payment_intent_id"] = it.paymentIntentId
        }
        
        reservation.holdExpiresAt?.let {
            payload["hold_expires_at"] = it.toString()
        }
        
        // TODO: Use Jackson ObjectMapper for proper JSON serialization
        return payload.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    }

    private fun calculateTotalAmount(quantity: Int): Long {
        // TODO: Integrate with pricing service
        return quantity * 50000L // 50,000 KRW per ticket
    }
}
