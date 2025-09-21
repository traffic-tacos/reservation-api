package com.traffictacos.reservation.controller

import com.traffictacos.reservation.dto.*
import com.traffictacos.reservation.service.ReservationService
import com.traffictacos.reservation.service.IdempotencyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.reactive.awaitFirst
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/v1/reservations")
@Tag(name = "Reservations", description = "Reservation management API")
class ReservationController(
    private val reservationService: ReservationService,
    private val idempotencyService: IdempotencyService
) {

    private val logger = LoggerFactory.getLogger(ReservationController::class.java)

    @PostMapping
    @Operation(
        summary = "Create a new reservation",
        description = "Creates a new reservation with a 60-second hold period"
    )
    @ApiResponse(responseCode = "201", description = "Reservation created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    @ApiResponse(responseCode = "409", description = "Seats not available or idempotency conflict")
    suspend fun createReservation(
        @Valid @RequestBody request: CreateReservationRequest,
        @Parameter(description = "Idempotency key for request deduplication", required = true)
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<CreateReservationResponse> {

        val userId = jwt.getClaimAsString("sub") ?: throw IllegalArgumentException("User ID not found in JWT")

        logger.info("Creating reservation for user: {}, eventId: {}", userId, request.eventId)

        // Handle idempotency
        val response = idempotencyService.executeIdempotent(
            idempotencyKey = idempotencyKey,
            request = request
        ) {
            reservationService.createReservation(request, userId, idempotencyKey)
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{reservationId}")
    @Operation(
        summary = "Get reservation details",
        description = "Retrieves details of a specific reservation"
    )
    @ApiResponse(responseCode = "200", description = "Reservation details retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Reservation not found")
    suspend fun getReservation(
        @PathVariable reservationId: String,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<ReservationDetailsResponse> {

        val userId = jwt.getClaimAsString("sub") ?: throw IllegalArgumentException("User ID not found in JWT")

        logger.debug("Getting reservation: {} for user: {}", reservationId, userId)

        val response = reservationService.getReservation(reservationId)

        return ResponseEntity.ok(response)
    }

    @PostMapping("/confirm")
    @Operation(
        summary = "Confirm a reservation",
        description = "Confirms a reservation after successful payment"
    )
    @ApiResponse(responseCode = "200", description = "Reservation confirmed successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request or reservation state")
    @ApiResponse(responseCode = "404", description = "Reservation not found")
    suspend fun confirmReservation(
        @Valid @RequestBody request: ConfirmReservationRequest,
        @Parameter(description = "Idempotency key for request deduplication")
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<ConfirmReservationResponse> {

        val userId = jwt.getClaimAsString("sub") ?: throw IllegalArgumentException("User ID not found in JWT")

        logger.info("Confirming reservation: {} for user: {}", request.reservationId, userId)

        val response = if (idempotencyKey != null) {
            idempotencyService.executeIdempotent(
                idempotencyKey = idempotencyKey,
                request = request
            ) {
                reservationService.confirmReservation(request, userId)
            }
        } else {
            reservationService.confirmReservation(request, userId)
        }

        return ResponseEntity.ok(response)
    }

    @PostMapping("/cancel")
    @Operation(
        summary = "Cancel a reservation",
        description = "Cancels a reservation and releases the held seats"
    )
    @ApiResponse(responseCode = "200", description = "Reservation cancelled successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request or reservation state")
    @ApiResponse(responseCode = "404", description = "Reservation not found")
    suspend fun cancelReservation(
        @Valid @RequestBody request: CancelReservationRequest,
        @Parameter(description = "Idempotency key for request deduplication")
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<CancelReservationResponse> {

        val userId = jwt.getClaimAsString("sub") ?: throw IllegalArgumentException("User ID not found in JWT")

        logger.info("Cancelling reservation: {} for user: {}", request.reservationId, userId)

        val response = if (idempotencyKey != null) {
            idempotencyService.executeIdempotent(
                idempotencyKey = idempotencyKey,
                request = request
            ) {
                reservationService.cancelReservation(request, userId)
            }
        } else {
            reservationService.cancelReservation(request, userId)
        }

        return ResponseEntity.ok(response)
    }
}