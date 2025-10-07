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

@GrpcService
class InventoryGrpcService : InventoryServiceGrpcKt.InventoryServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(InventoryGrpcService::class.java)

    // 🔴 PoC Mode: Unlimited inventory for infrastructure testing
    // No seat limits, always available, minimal state management
    init {
        logger.info("InventoryGrpcService initialized in PoC mode (unlimited inventory)")
    }

    override suspend fun checkAvailability(request: CheckAvailabilityRequest): CheckAvailabilityResponse {
        logger.debug("PoC: Checking availability for eventId: {}, quantity: {} (always available)", request.eventId, request.quantity)

        return try {
            val responseBuilder = CheckAvailabilityResponse.newBuilder()
                .setAvailable(true)
                .setRemainingInSection(999999) // PoC: Unlimited

            // Return requested seats or generate seat IDs
            val seatsToReturn = if (request.seatIdsList.isNotEmpty()) {
                request.seatIdsList
            } else {
                // Generate seat IDs: A-1, A-2, ... based on quantity
                (1..request.quantity).map { "A-$it" }
            }

            // Add seats to response
            seatsToReturn.forEach { seatId ->
                responseBuilder.addAvailableSeats(createSeat(seatId, SeatStatus.SEAT_STATUS_AVAILABLE))
            }

            logger.debug("PoC: Returning {} available seats for event {}", seatsToReturn.size, request.eventId)
            responseBuilder.build()

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
        logger.info("PoC: Holding seats for eventId: {}, seatIds: {}, reservationId: {} (always succeeds)",
                   request.eventId, request.seatIdsList, request.reservationId)

        return try {
            val holdToken = UUID.randomUUID().toString()
            val expiresAt = Instant.now().plusSeconds(60L)
            
            val responseBuilder = ReserveSeatResponse.newBuilder()
                .setHoldId(holdToken)
                .setStatus(com.traffic_tacos.reservation.v1.HoldStatus.HOLD_STATUS_ACTIVE)

            // 🔴 CRITICAL FIX: Add reserved_seats to response
            request.seatIdsList.forEach { seatId ->
                responseBuilder.addReservedSeats(createSeat(seatId, SeatStatus.SEAT_STATUS_RESERVED))
            }

            logger.info("PoC: Successfully held {} seats for reservation {}", request.seatIdsList.size, request.reservationId)
            responseBuilder.build()

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
        logger.info("PoC: Committing reservation: {}, eventId: {}, seatIds: {} (always succeeds)",
                   request.reservationId, request.eventId, request.seatIdsList)

        return try {
            val orderId = UUID.randomUUID().toString()

            logger.info("PoC: Successfully committed reservation {} with {} seats", request.reservationId, request.seatIdsList.size)

            CommitReservationResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus(com.traffic_tacos.reservation.v1.CommitStatus.COMMIT_STATUS_SUCCESS)
                .build()

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
        logger.info("PoC: Releasing hold for reservation: {}, eventId: {}, seatIds: {} (always succeeds)",
                   request.reservationId, request.eventId, request.seatIdsList)

        return try {
            logger.info("PoC: Released hold for {} seats from reservation {}", request.seatIdsList.size, request.reservationId)

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

    // Helper function to create Seat objects
    private fun createSeat(seatId: String, status: SeatStatus): Seat {
        val parts = seatId.split("-")
        return Seat.newBuilder()
            .setId(seatId)
            .setSection(if (parts.size > 1) parts[0] else "A")
            .setNumber(if (parts.size > 1) parts[1] else seatId)
            .setStatus(status)
            .build()
    }
}