package com.traffictacos.reservation.grpc

import reservationv1.InventoryServiceGrpcKt
import reservationv1.CheckAvailabilityRequest
import reservationv1.CheckAvailabilityResponse
import reservationv1.HoldSeatsRequest
import reservationv1.HoldSeatsResponse
import reservationv1.CommitReservationRequest
import reservationv1.CommitReservationResponse
import reservationv1.ReleaseHoldRequest
import reservationv1.ReleaseHoldResponse
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.timelimiter.annotation.TimeLimiter
import kotlinx.coroutines.reactive.awaitFirst
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class InventoryGrpcClient(
    private val inventoryStub: InventoryServiceGrpcKt.InventoryServiceCoroutineStub
) {

    private val logger = LoggerFactory.getLogger(InventoryGrpcClient::class.java)

    @CircuitBreaker(name = "inventory-grpc", fallbackMethod = "checkAvailabilityFallback")
    @TimeLimiter(name = "inventory-grpc")
    suspend fun checkAvailability(
        eventId: String,
        quantity: Int,
        seatIds: List<String> = emptyList(),
        userId: String
    ): CheckAvailabilityResponse {
        logger.debug("Checking availability for eventId: {}, quantity: {}, seatIds: {}, userId: {}", eventId, quantity, seatIds, userId)

        val request = CheckAvailabilityRequest.newBuilder()
            .setEventId(eventId)
            .setQuantity(quantity)
            .addAllSeatIds(seatIds)
            .setUserId(userId)
            .build()

        return try {
            val response = inventoryStub.checkAvailability(request)
            logger.debug("Availability check response: {}", response)
            response
        } catch (e: Exception) {
            logger.error("Error checking availability for eventId: {}", eventId, e)
            throw e
        }
    }

    @CircuitBreaker(name = "inventory-grpc", fallbackMethod = "holdSeatsFallback")
    @TimeLimiter(name = "inventory-grpc")
    suspend fun holdSeats(
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        reservationId: String,
        userId: String,
        holdDurationSeconds: Int = 60
    ): HoldSeatsResponse {
        logger.debug("Holding seats for eventId: {}, seatIds: {}, reservationId: {}, userId: {}", eventId, seatIds, reservationId, userId)

        val request = HoldSeatsRequest.newBuilder()
            .setEventId(eventId)
            .addAllSeatIds(seatIds)
            .setQuantity(quantity)
            .setReservationId(reservationId)
            .setUserId(userId)
            .setHoldDurationSeconds(holdDurationSeconds)
            .build()

        return try {
            val response = inventoryStub.holdSeats(request)
            logger.debug("Hold seats response: {}", response)
            response
        } catch (e: Exception) {
            logger.error("Error holding seats for eventId: {}", eventId, e)
            throw e
        }
    }

    @CircuitBreaker(name = "inventory-grpc", fallbackMethod = "commitReservationFallback")
    @TimeLimiter(name = "inventory-grpc")
    suspend fun commitReservation(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        paymentIntentId: String,
        holdToken: String,
        userId: String
    ): CommitReservationResponse {
        logger.debug("Committing reservation: {}, eventId: {}, seatIds: {}, userId: {}", reservationId, eventId, seatIds, userId)

        val request = CommitReservationRequest.newBuilder()
            .setReservationId(reservationId)
            .setEventId(eventId)
            .addAllSeatIds(seatIds)
            .setQuantity(quantity)
            .setPaymentIntentId(paymentIntentId)
            .setHoldToken(holdToken)
            .setUserId(userId)
            .build()

        return try {
            val response = inventoryStub.commitReservation(request)
            logger.debug("Commit reservation response: {}", response)
            response
        } catch (e: Exception) {
            logger.error("Error committing reservation: {}", reservationId, e)
            throw e
        }
    }

    @CircuitBreaker(name = "inventory-grpc", fallbackMethod = "releaseHoldFallback")
    @TimeLimiter(name = "inventory-grpc")
    suspend fun releaseHold(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        holdToken: String,
        userId: String
    ): ReleaseHoldResponse {
        logger.debug("Releasing hold for reservation: {}, eventId: {}, seatIds: {}, userId: {}", reservationId, eventId, seatIds, userId)

        val request = ReleaseHoldRequest.newBuilder()
            .setReservationId(reservationId)
            .setEventId(eventId)
            .addAllSeatIds(seatIds)
            .setQuantity(quantity)
            .setHoldToken(holdToken)
            .setUserId(userId)
            .build()

        return try {
            val response = inventoryStub.releaseHold(request)
            logger.debug("Release hold response: {}", response)
            response
        } catch (e: Exception) {
            logger.error("Error releasing hold for reservation: {}", reservationId, e)
            throw e
        }
    }

    // Fallback methods
    suspend fun checkAvailabilityFallback(
        eventId: String,
        quantity: Int,
        seatIds: List<String>,
        userId: String,
        ex: Exception
    ): CheckAvailabilityResponse {
        logger.warn("Fallback for checkAvailability: {}", ex.message)
        return CheckAvailabilityResponse.newBuilder()
            .setAvailable(false)
            .setMessage("Service temporarily unavailable")
            .build()
    }

    suspend fun holdSeatsFallback(
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        reservationId: String,
        userId: String,
        holdDurationSeconds: Int,
        ex: Exception
    ): HoldSeatsResponse {
        logger.warn("Fallback for holdSeats: {}", ex.message)
        return HoldSeatsResponse.newBuilder()
            .setSuccess(false)
            .setMessage("Service temporarily unavailable")
            .build()
    }

    suspend fun commitReservationFallback(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        paymentIntentId: String,
        holdToken: String,
        userId: String,
        ex: Exception
    ): CommitReservationResponse {
        logger.warn("Fallback for commitReservation: {}", ex.message)
        return CommitReservationResponse.newBuilder()
            .setSuccess(false)
            .setMessage("Service temporarily unavailable")
            .build()
    }

    suspend fun releaseHoldFallback(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        holdToken: String,
        userId: String,
        ex: Exception
    ): ReleaseHoldResponse {
        logger.warn("Fallback for releaseHold: {}", ex.message)
        return ReleaseHoldResponse.newBuilder()
            .setSuccess(false)
            .setMessage("Service temporarily unavailable")
            .build()
    }
}