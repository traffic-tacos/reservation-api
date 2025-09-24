package com.traffictacos.reservation.grpc

import com.traffic_tacos.reservation.v1.InventoryServiceGrpcKt
import com.traffic_tacos.reservation.v1.CheckAvailabilityRequest
import com.traffic_tacos.reservation.v1.CheckAvailabilityResponse
import com.traffic_tacos.reservation.v1.ReserveSeatRequest
import com.traffic_tacos.reservation.v1.ReserveSeatResponse
import com.traffic_tacos.reservation.v1.CommitReservationRequest
import com.traffic_tacos.reservation.v1.CommitReservationResponse
import com.traffic_tacos.reservation.v1.ReleaseHoldRequest
import com.traffic_tacos.reservation.v1.ReleaseHoldResponse
import com.traffic_tacos.common.v1.Seat
import com.traffic_tacos.common.v1.SeatStatus
import com.traffic_tacos.common.v1.Error
import com.traffic_tacos.common.v1.ErrorCode
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
                    val responseBuilder = CheckAvailabilityResponse.newBuilder()
                        .setAvailable(true)
                        .setRemainingInSection(availableSeats.size)

                    // Add available seats as Seat objects
                    availableRequestedSeats.take(request.quantity).forEach { seatId ->
                        val parts = seatId.split("-")
                        responseBuilder.addAvailableSeats(
                            Seat.newBuilder()
                                .setId(seatId)
                                .setSection(if (parts.size > 1) parts[0] else "A")
                                .setNumber(if (parts.size > 1) parts[1] else seatId)
                                .setStatus(SeatStatus.SEAT_STATUS_AVAILABLE)
                                .build()
                        )
                    }

                    responseBuilder.build()
                } else {
                    CheckAvailabilityResponse.newBuilder()
                        .setAvailable(false)
                        .setRemainingInSection(availableSeats.size)
                        .setError(
                            Error.newBuilder()
                                .setCode(com.traffic_tacos.common.v1.ErrorCode.ERROR_CODE_INSUFFICIENT_INVENTORY)
                                .setMessage("Requested seats are not available")
                                .build()
                        )
                        .build()
                }
            } else {
                // Check general availability
                if (availableSeats.size >= request.quantity) {
                    val responseBuilder = CheckAvailabilityResponse.newBuilder()
                        .setAvailable(true)
                        .setRemainingInSection(availableSeats.size)

                    // Add available seats as Seat objects
                    availableSeats.take(request.quantity).forEach { seatId ->
                        val parts = seatId.split("-")
                        responseBuilder.addAvailableSeats(
                            Seat.newBuilder()
                                .setId(seatId)
                                .setSection(if (parts.size > 1) parts[0] else "A")
                                .setNumber(if (parts.size > 1) parts[1] else seatId)
                                .setStatus(SeatStatus.SEAT_STATUS_AVAILABLE)
                                .build()
                        )
                    }

                    responseBuilder.build()
                } else {
                    CheckAvailabilityResponse.newBuilder()
                        .setAvailable(false)
                        .setRemainingInSection(availableSeats.size)
                        .setError(
                            Error.newBuilder()
                                .setCode(com.traffic_tacos.common.v1.ErrorCode.ERROR_CODE_INSUFFICIENT_INVENTORY)
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
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    override suspend fun reserveSeat(request: ReserveSeatRequest): ReserveSeatResponse {
        logger.info("Holding seats for eventId: {}, seatIds: {}, reservationId: {}",
                   request.eventId, request.seatIdsList, request.reservationId)

        return try {
            val availableSeats = getAvailableSeats(request.eventId)
            val requestedSeats = request.seatIdsList

            // Check if all requested seats are available
            val canHold = requestedSeats.all { it in availableSeats }

            if (canHold) {
                val holdToken = UUID.randomUUID().toString()
                val expiresAt = Instant.now().plusSeconds(60L) // 60 seconds default hold

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

                ReserveSeatResponse.newBuilder()
                    .setHoldId(holdToken)
                    .setStatus(com.traffic_tacos.reservation.v1.HoldStatus.HOLD_STATUS_ACTIVE)
                    .build()
            } else {
                val unavailableSeats = requestedSeats.filter { it !in availableSeats }
                logger.warn("Cannot hold seats, some seats unavailable: {}", unavailableSeats)

                ReserveSeatResponse.newBuilder()
                    .setStatus(com.traffic_tacos.reservation.v1.HoldStatus.HOLD_STATUS_UNSPECIFIED)
                    .setError(
                        com.traffic_tacos.common.v1.Error.newBuilder()
                            .setMessage("Some seats are not available: $unavailableSeats")
                            .build()
                    )
                    .build()
            }
        } catch (e: Exception) {
            logger.error("Error holding seats for eventId: {}", request.eventId, e)
            ReserveSeatResponse.newBuilder()
                .setStatus(com.traffic_tacos.reservation.v1.HoldStatus.HOLD_STATUS_UNSPECIFIED)
                .setError(
                    com.traffic_tacos.common.v1.Error.newBuilder()
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
            // Generate hold token since it's not in request
            val holdToken = UUID.randomUUID().toString()

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
                    .setOrderId(confirmationId)
                    .setStatus(com.traffic_tacos.reservation.v1.CommitStatus.COMMIT_STATUS_SUCCESS)
                    .build()
            } else {
                val invalidSeats = requestedSeats - validSeats.toSet()
                logger.warn("Cannot commit reservation, invalid hold for seats: {}", invalidSeats)

                CommitReservationResponse.newBuilder()
                    .setStatus(com.traffic_tacos.reservation.v1.CommitStatus.COMMIT_STATUS_FAILED_EXPIRED)
                    .setError(
                        com.traffic_tacos.common.v1.Error.newBuilder()
                            .setMessage("Hold expired or invalid for some seats: $invalidSeats")
                            .build()
                    )
                    .build()
            }
        } catch (e: Exception) {
            logger.error("Error committing reservation: {}", request.reservationId, e)
            CommitReservationResponse.newBuilder()
                .setStatus(com.traffic_tacos.reservation.v1.CommitStatus.COMMIT_STATUS_FAILED_CONFLICT)
                .setError(
                    com.traffic_tacos.common.v1.Error.newBuilder()
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
            // Generate hold token since it's not in request
            val holdToken = UUID.randomUUID().toString()

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
                .setStatus(com.traffic_tacos.reservation.v1.ReleaseStatus.RELEASE_STATUS_SUCCESS)
                .build()

        } catch (e: Exception) {
            logger.error("Error releasing hold for reservation: {}", request.reservationId, e)
            ReleaseHoldResponse.newBuilder()
                .setStatus(com.traffic_tacos.reservation.v1.ReleaseStatus.RELEASE_STATUS_FAILED)
                .setError(
                    com.traffic_tacos.common.v1.Error.newBuilder()
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