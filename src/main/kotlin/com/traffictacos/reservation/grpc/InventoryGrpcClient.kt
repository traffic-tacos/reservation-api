package com.traffictacos.reservation.grpc

import com.traffictacos.inventory.v1.InventoryGrpcKt
import com.traffictacos.inventory.v1.CheckAvailabilityRequest
import com.traffictacos.inventory.v1.CheckAvailabilityResponse
import com.traffictacos.inventory.v1.CommitReservationRequest
import com.traffictacos.inventory.v1.CommitReservationResponse
import com.traffictacos.inventory.v1.ReleaseHoldRequest
import com.traffictacos.inventory.v1.ReleaseHoldResponse
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.timelimiter.annotation.TimeLimiter
import kotlinx.coroutines.reactive.awaitFirst
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class InventoryGrpcClient(
    private val inventoryStub: InventoryGrpcKt.InventoryCoroutineStub
) {

    private val logger = LoggerFactory.getLogger(InventoryGrpcClient::class.java)

    @CircuitBreaker(name = "inventory-grpc", fallbackMethod = "checkAvailabilityFallback")
    @TimeLimiter(name = "inventory-grpc")
    suspend fun checkAvailability(
        eventId: String,
        quantity: Int,
        seatIds: List<String> = emptyList()
    ): CheckAvailabilityResponse {
        logger.debug("Checking availability for eventId: {}, quantity: {}, seatIds: {}", eventId, quantity, seatIds)

        val request = CheckAvailabilityRequest.newBuilder()
            .setEventId(eventId)
            .setQuantity(quantity)
            .addAllSeatIds(seatIds)
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

    @CircuitBreaker(name = "inventory-grpc", fallbackMethod = "commitReservationFallback")
    @TimeLimiter(name = "inventory-grpc")
    suspend fun commitReservation(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        paymentIntentId: String
    ): CommitReservationResponse {
        logger.debug("Committing reservation: {}, eventId: {}, seatIds: {}", reservationId, eventId, seatIds)

        val request = CommitReservationRequest.newBuilder()
            .setReservationId(reservationId)
            .setEventId(eventId)
            .addAllSeatIds(seatIds)
            .setQuantity(quantity)
            .setPaymentIntentId(paymentIntentId)
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
        quantity: Int
    ): ReleaseHoldResponse {
        logger.debug("Releasing hold for reservation: {}, eventId: {}, seatIds: {}", reservationId, eventId, seatIds)

        val request = ReleaseHoldRequest.newBuilder()
            .setReservationId(reservationId)
            .setEventId(eventId)
            .addAllSeatIds(seatIds)
            .setQuantity(quantity)
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
        ex: Exception
    ): CheckAvailabilityResponse {
        logger.warn("Fallback for checkAvailability: {}", ex.message)
        return CheckAvailabilityResponse.newBuilder()
            .setAvailable(false)
            .setMessage("Service temporarily unavailable")
            .build()
    }

    suspend fun commitReservationFallback(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        paymentIntentId: String,
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
        ex: Exception
    ): ReleaseHoldResponse {
        logger.warn("Fallback for releaseHold: {}", ex.message)
        return ReleaseHoldResponse.newBuilder()
            .setSuccess(false)
            .setMessage("Service temporarily unavailable")
            .build()
    }
}