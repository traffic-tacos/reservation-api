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
import commonv1.Error
import commonv1.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import net.devh.boot.grpc.server.service.GrpcService
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@GrpcService
class InventoryGrpcService : InventoryServiceGrpcKt.InventoryServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(InventoryGrpcService::class.java)

    // Mock inventory data - in real implementation this would be DynamoDB
    private val eventSeats = ConcurrentHashMap<String, MutableSet<String>>()
    private val heldSeats = ConcurrentHashMap<String, HoldInfo>()

    init {
        // Initialize some mock events and seats
        initializeMockData()
    }

    override suspend fun checkAvailability(request: CheckAvailabilityRequest): CheckAvailabilityResponse {
        logger.debug("Checking availability for eventId: {}, quantity: {}", request.eventId, request.quantity)

        return try {
            val availableSeats = getAvailableSeats(request.eventId)

            if (request.seatIdsList.isNotEmpty()) {
                // Check specific seats
                val requestedSeats = request.seatIdsList
                val availableRequestedSeats = requestedSeats.filter { it in availableSeats }

                if (availableRequestedSeats.size >= request.quantity) {
                    CheckAvailabilityResponse.newBuilder()
                        .setAvailable(true)
                        .addAllAvailableSeatIds(availableRequestedSeats.take(request.quantity))
                        .setTotalAvailable(availableSeats.size)
                        .setMessage("Requested seats are available")
                        .build()
                } else {
                    CheckAvailabilityResponse.newBuilder()
                        .setAvailable(false)
                        .setTotalAvailable(availableSeats.size)
                        .setMessage("Some requested seats are not available")
                        .setError(
                            Error.newBuilder()
                                .setCode(ErrorCode.ERROR_CODE_SEATS_UNAVAILABLE)
                                .setMessage("Requested seats are not available")
                                .build()
                        )
                        .build()
                }
            } else {
                // Check general availability
                if (availableSeats.size >= request.quantity) {
                    CheckAvailabilityResponse.newBuilder()
                        .setAvailable(true)
                        .addAllAvailableSeatIds(availableSeats.take(request.quantity))
                        .setTotalAvailable(availableSeats.size)
                        .setMessage("Seats are available")
                        .build()
                } else {
                    CheckAvailabilityResponse.newBuilder()
                        .setAvailable(false)
                        .setTotalAvailable(availableSeats.size)
                        .setMessage("Not enough seats available")
                        .setError(
                            Error.newBuilder()
                                .setCode(ErrorCode.ERROR_CODE_SEATS_UNAVAILABLE)
                                .setMessage("Not enough seats available")
                                .build()
                        )
                        .build()
                }
            }
        } catch (e: Exception) {
            logger.error("Error checking availability for eventId: {}", request.eventId, e)
            CheckAvailabilityResponse.newBuilder()
                .setAvailable(false)
                .setError(
                    Error.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL_ERROR)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    override suspend fun holdSeats(request: HoldSeatsRequest): HoldSeatsResponse {
        logger.info("Holding seats for eventId: {}, seatIds: {}, reservationId: {}",
                   request.eventId, request.seatIdsList, request.reservationId)

        return try {
            val availableSeats = getAvailableSeats(request.eventId)
            val requestedSeats = request.seatIdsList

            // Check if all requested seats are available
            val canHold = requestedSeats.all { it in availableSeats }

            if (canHold) {
                val holdToken = UUID.randomUUID().toString()
                val expiresAt = Instant.now().plusSeconds(request.holdDurationSeconds.toLong())

                // Hold the seats
                requestedSeats.forEach { seatId ->
                    heldSeats[seatId] = HoldInfo(
                        reservationId = request.reservationId,
                        holdToken = holdToken,
                        expiresAt = expiresAt,
                        userId = request.userId
                    )
                }

                logger.info("Successfully held {} seats for reservation {}", requestedSeats.size, request.reservationId)

                HoldSeatsResponse.newBuilder()
                    .setSuccess(true)
                    .addAllHeldSeatIds(requestedSeats)
                    .setHoldToken(holdToken)
                    .setExpiresAt(expiresAt.epochSecond)
                    .setMessage("Seats held successfully")
                    .build()
            } else {
                val unavailableSeats = requestedSeats.filter { it !in availableSeats }
                logger.warn("Cannot hold seats, some seats unavailable: {}", unavailableSeats)

                HoldSeatsResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Some seats are not available: $unavailableSeats")
                    .setError(
                        Error.newBuilder()
                            .setCode(ErrorCode.ERROR_CODE_SEATS_UNAVAILABLE)
                            .setMessage("Some seats are not available")
                            .build()
                    )
                    .build()
            }
        } catch (e: Exception) {
            logger.error("Error holding seats for eventId: {}", request.eventId, e)
            HoldSeatsResponse.newBuilder()
                .setSuccess(false)
                .setError(
                    Error.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL_ERROR)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    override suspend fun commitReservation(request: CommitReservationRequest): CommitReservationResponse {
        logger.info("Committing reservation: {}, eventId: {}, seatIds: {}",
                   request.reservationId, request.eventId, request.seatIdsList)

        return try {
            val requestedSeats = request.seatIdsList
            val holdToken = request.holdToken

            // Verify hold tokens and remove from held seats
            val validSeats = requestedSeats.filter { seatId ->
                val holdInfo = heldSeats[seatId]
                holdInfo != null &&
                holdInfo.holdToken == holdToken &&
                holdInfo.reservationId == request.reservationId &&
                holdInfo.expiresAt.isAfter(Instant.now())
            }

            if (validSeats.size == requestedSeats.size) {
                // Remove seats from available inventory and held seats
                requestedSeats.forEach { seatId ->
                    eventSeats[request.eventId]?.remove(seatId)
                    heldSeats.remove(seatId)
                }

                val confirmationId = UUID.randomUUID().toString()

                logger.info("Successfully committed reservation {} with {} seats", request.reservationId, validSeats.size)

                CommitReservationResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Reservation committed successfully")
                    .addAllConfirmedSeatIds(validSeats)
                    .setConfirmationId(confirmationId)
                    .build()
            } else {
                val invalidSeats = requestedSeats - validSeats.toSet()
                logger.warn("Cannot commit reservation, invalid hold for seats: {}", invalidSeats)

                CommitReservationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Invalid hold for some seats: $invalidSeats")
                    .setError(
                        Error.newBuilder()
                            .setCode(ErrorCode.ERROR_CODE_HOLD_EXPIRED)
                            .setMessage("Hold expired or invalid for some seats")
                            .build()
                    )
                    .build()
            }
        } catch (e: Exception) {
            logger.error("Error committing reservation: {}", request.reservationId, e)
            CommitReservationResponse.newBuilder()
                .setSuccess(false)
                .setError(
                    Error.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL_ERROR)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    override suspend fun releaseHold(request: ReleaseHoldRequest): ReleaseHoldResponse {
        logger.info("Releasing hold for reservation: {}, eventId: {}, seatIds: {}",
                   request.reservationId, request.eventId, request.seatIdsList)

        return try {
            val requestedSeats = request.seatIdsList
            val holdToken = request.holdToken

            // Verify hold tokens and remove holds
            val releasedSeats = requestedSeats.filter { seatId ->
                val holdInfo = heldSeats[seatId]
                if (holdInfo != null &&
                    holdInfo.holdToken == holdToken &&
                    holdInfo.reservationId == request.reservationId) {
                    heldSeats.remove(seatId)
                    true
                } else {
                    false
                }
            }

            logger.info("Released hold for {} seats from reservation {}", releasedSeats.size, request.reservationId)

            ReleaseHoldResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Hold released successfully")
                .addAllReleasedSeatIds(releasedSeats)
                .build()

        } catch (e: Exception) {
            logger.error("Error releasing hold for reservation: {}", request.reservationId, e)
            ReleaseHoldResponse.newBuilder()
                .setSuccess(false)
                .setError(
                    Error.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL_ERROR)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    private fun getAvailableSeats(eventId: String): List<String> {
        // Clean up expired holds first
        cleanupExpiredHolds()

        val allSeats = eventSeats[eventId] ?: emptySet()
        val heldSeatIds = heldSeats.keys

        return allSeats.filter { it !in heldSeatIds }
    }

    private fun cleanupExpiredHolds() {
        val now = Instant.now()
        val expiredHolds = heldSeats.filter { (_, holdInfo) -> holdInfo.expiresAt.isBefore(now) }
        expiredHolds.forEach { (seatId, _) ->
            heldSeats.remove(seatId)
        }
        if (expiredHolds.isNotEmpty()) {
            logger.debug("Cleaned up {} expired holds", expiredHolds.size)
        }
    }

    private fun initializeMockData() {
        // Initialize mock events with seats
        val events = listOf("evt_2025_concert", "evt_2025_sports", "evt_2025_theater")

        events.forEach { eventId ->
            val seats = mutableSetOf<String>()
            // Create seats A-1 to A-50, B-1 to B-50
            ('A'..'B').forEach { section ->
                (1..50).forEach { number ->
                    seats.add("$section-$number")
                }
            }
            eventSeats[eventId] = seats
        }

        logger.info("Initialized mock inventory data for {} events", events.size)
    }

    data class HoldInfo(
        val reservationId: String,
        val holdToken: String,
        val expiresAt: Instant,
        val userId: String
    )
}