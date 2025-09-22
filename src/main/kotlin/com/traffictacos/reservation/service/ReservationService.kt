package com.traffictacos.reservation.service

import com.traffictacos.reservation.domain.Reservation
import com.traffictacos.reservation.domain.ReservationStatus
import com.traffictacos.reservation.domain.Order
import com.traffictacos.reservation.domain.OrderStatus
import com.traffictacos.reservation.dto.*
import com.traffictacos.reservation.grpc.InventoryGrpcService
import com.traffictacos.reservation.repository.ReservationRepository
import reservationv1.CheckAvailabilityRequest
import reservationv1.HoldSeatsRequest
import reservationv1.CommitReservationRequest
import reservationv1.ReleaseHoldRequest
import com.traffictacos.reservation.repository.OrderRepository
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@Service
class ReservationService(
    private val reservationRepository: ReservationRepository,
    private val orderRepository: OrderRepository,
    private val inventoryGrpcService: InventoryGrpcService,
    private val reservationExpiryService: ReservationExpiryService,
    private val outboxEventPublisher: OutboxEventPublisher
) {

    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    suspend fun createReservation(
        request: CreateReservationRequest,
        userId: String,
        idempotencyKey: String
    ): CreateReservationResponse {
        logger.info("Creating reservation for user: {}, eventId: {}, quantity: {}", userId, request.eventId, request.quantity)

        // Check inventory availability
        val availabilityRequest = CheckAvailabilityRequest.newBuilder()
            .setEventId(request.eventId)
            .setQuantity(request.quantity)
            .addAllSeatIds(request.seatIds)
            .setUserId(userId)
            .build()

        val availabilityResponse = inventoryGrpcService.checkAvailability(availabilityRequest)

        if (!availabilityResponse.available) {
            throw ReservationException(
                ErrorCode.SEAT_UNAVAILABLE,
                "Requested seats are not available: ${availabilityResponse.message}"
            )
        }

        // Create reservation with 60-second hold
        val reservationId = UUID.randomUUID().toString()
        val holdExpiresAt = Instant.now().plusSeconds(60)

        // Hold seats in inventory service
        val holdRequest = HoldSeatsRequest.newBuilder()
            .setEventId(request.eventId)
            .addAllSeatIds(availabilityResponse.availableSeatIdsList)
            .setQuantity(request.quantity)
            .setReservationId(reservationId)
            .setUserId(userId)
            .setHoldDurationSeconds(60)
            .build()

        val holdResponse = inventoryGrpcService.holdSeats(holdRequest)

        if (!holdResponse.success) {
            throw ReservationException(
                ErrorCode.INVENTORY_SERVICE_ERROR,
                "Failed to hold seats: ${holdResponse.message}"
            )
        }

        val reservation = Reservation(
            reservationId = reservationId,
            eventId = request.eventId,
            userId = userId,
            quantity = request.quantity,
            seatIds = holdResponse.heldSeatIdsList,
            status = ReservationStatus.HOLD,
            holdExpiresAt = holdExpiresAt,
            holdToken = holdResponse.holdToken,
            idempotencyKey = idempotencyKey
        )

        val savedReservation = reservationRepository.saveAsync(reservation)

        // Schedule expiry
        reservationExpiryService.scheduleExpiry(reservationId, holdExpiresAt)

        // Publish event
        outboxEventPublisher.publishReservationCreated(savedReservation)

        logger.info("Reservation created: {}, expires at: {}", reservationId, holdExpiresAt)

        return CreateReservationResponse(
            reservationId = reservationId,
            status = ReservationStatus.HOLD,
            holdExpiresAt = holdExpiresAt,
            message = "Reservation created successfully"
        )
    }

    suspend fun getReservation(reservationId: String): ReservationDetailsResponse {
        val reservation = reservationRepository.findByIdAsync(reservationId)
            ?: throw ReservationException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found: $reservationId")

        return ReservationDetailsResponse(
            reservationId = reservation.reservationId,
            eventId = reservation.eventId,
            quantity = reservation.quantity,
            seatIds = reservation.seatIds,
            status = reservation.status,
            holdExpiresAt = reservation.holdExpiresAt,
            createdAt = reservation.createdAt,
            updatedAt = reservation.updatedAt
        )
    }

    suspend fun confirmReservation(
        request: ConfirmReservationRequest,
        userId: String
    ): ConfirmReservationResponse {
        logger.info("Confirming reservation: {}, paymentIntentId: {}", request.reservationId, request.paymentIntentId)

        val reservation = reservationRepository.findByIdAsync(request.reservationId)
            ?: throw ReservationException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found")

        // Validate reservation status
        when (reservation.status) {
            ReservationStatus.EXPIRED -> throw ReservationException(ErrorCode.RESERVATION_EXPIRED, "Reservation has expired")
            ReservationStatus.CANCELLED -> throw ReservationException(ErrorCode.RESERVATION_ALREADY_CANCELLED, "Reservation is cancelled")
            ReservationStatus.CONFIRMED -> throw ReservationException(ErrorCode.RESERVATION_ALREADY_CONFIRMED, "Reservation is already confirmed")
            else -> {}
        }

        // Check if reservation is still valid (not expired)
        if (reservation.holdExpiresAt?.isBefore(Instant.now()) == true) {
            reservation.status = ReservationStatus.EXPIRED
            reservationRepository.saveAsync(reservation)
            throw ReservationException(ErrorCode.RESERVATION_EXPIRED, "Reservation has expired")
        }

        // Commit reservation in inventory service
        val commitRequest = CommitReservationRequest.newBuilder()
            .setReservationId(request.reservationId)
            .setEventId(reservation.eventId)
            .addAllSeatIds(reservation.seatIds)
            .setQuantity(reservation.quantity)
            .setPaymentIntentId(request.paymentIntentId)
            .setHoldToken(reservation.holdToken ?: "")
            .setUserId(userId)
            .build()

        val commitResponse = inventoryGrpcService.commitReservation(commitRequest)

        if (!commitResponse.success) {
            throw ReservationException(ErrorCode.INVENTORY_SERVICE_ERROR, "Failed to commit reservation: ${commitResponse.message}")
        }

        // Update reservation status
        reservation.status = ReservationStatus.CONFIRMED
        reservation.updatedAt = Instant.now()
        reservationRepository.saveAsync(reservation)

        // Create order
        val orderId = UUID.randomUUID().toString()
        val order = Order(
            orderId = orderId,
            reservationId = request.reservationId,
            eventId = reservation.eventId,
            userId = userId,
            amount = BigDecimal.valueOf(reservation.quantity * 100.0), // Mock price: $100 per seat
            status = OrderStatus.CONFIRMED,
            paymentIntentId = request.paymentIntentId
        )

        orderRepository.saveAsync(order)

        // Publish event
        outboxEventPublisher.publishReservationConfirmed(reservation, order)

        logger.info("Reservation confirmed: {}, orderId: {}", request.reservationId, orderId)

        return ConfirmReservationResponse(
            orderId = orderId,
            reservationId = request.reservationId,
            status = ReservationStatus.CONFIRMED,
            message = "Reservation confirmed successfully"
        )
    }

    suspend fun cancelReservation(
        request: CancelReservationRequest,
        userId: String
    ): CancelReservationResponse {
        logger.info("Cancelling reservation: {}", request.reservationId)

        val reservation = reservationRepository.findByIdAsync(request.reservationId)
            ?: throw ReservationException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found")

        // Validate reservation status
        when (reservation.status) {
            ReservationStatus.CANCELLED -> throw ReservationException(ErrorCode.RESERVATION_ALREADY_CANCELLED, "Reservation is already cancelled")
            ReservationStatus.EXPIRED -> throw ReservationException(ErrorCode.RESERVATION_EXPIRED, "Reservation has expired")
            else -> {}
        }

        // Release hold in inventory service
        val releaseRequest = ReleaseHoldRequest.newBuilder()
            .setReservationId(request.reservationId)
            .setEventId(reservation.eventId)
            .addAllSeatIds(reservation.seatIds)
            .setQuantity(reservation.quantity)
            .setHoldToken(reservation.holdToken ?: "")
            .setUserId(userId)
            .build()

        val releaseResponse = inventoryGrpcService.releaseHold(releaseRequest)

        if (!releaseResponse.success) {
            logger.warn("Failed to release hold in inventory service: {}", releaseResponse.message)
        }

        // Update reservation status
        reservation.status = ReservationStatus.CANCELLED
        reservation.updatedAt = Instant.now()
        reservationRepository.saveAsync(reservation)

        // Publish event
        outboxEventPublisher.publishReservationCancelled(reservation)

        logger.info("Reservation cancelled: {}", request.reservationId)

        return CancelReservationResponse(
            reservationId = request.reservationId,
            status = ReservationStatus.CANCELLED,
            message = "Reservation cancelled successfully"
        )
    }
}

class ReservationException(
    val errorCode: ErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)