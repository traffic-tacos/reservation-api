package com.traffictacos.reservation.controller

import com.traffictacos.reservation.dto.*
import com.traffictacos.reservation.service.ReservationService
import io.micrometer.core.annotation.Timed
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

@RestController
@RequestMapping("/v1/reservations")
@Validated
@Timed("reservation_controller")
class ReservationController(
    private val reservationService: ReservationService
) {
    private val logger = LoggerFactory.getLogger(ReservationController::class.java)

    @PostMapping
    @Timed("reservation_create")
    fun createReservation(
        @RequestBody @Valid request: CreateReservationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String
    ): Mono<ResponseEntity<CreateReservationResponse>> {
        logger.info("Received reservation creation request for event: {}, user: {}",
            request.eventId, request.userId)

        return reservationService.createReservation(request)
            .map { response ->
                logger.info("Reservation created successfully: {}", response.reservationId)
                ResponseEntity.status(HttpStatus.CREATED).body(response)
            }
            .onErrorResume { error ->
                logger.error("Failed to create reservation", error)
                handleError(error)
            }
    }

    @PostMapping("/{reservationId}/confirm")
    @Timed("reservation_confirm")
    fun confirmReservation(
        @PathVariable @NotBlank reservationId: String,
        @RequestBody @Valid request: ConfirmReservationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String
    ): Mono<ResponseEntity<ConfirmReservationResponse>> {
        logger.info("Received reservation confirmation request: {}", reservationId)

        // Override reservationId from path parameter
        val confirmRequest = request.copy(reservationId = reservationId)

        return reservationService.confirmReservation(confirmRequest)
            .map { response ->
                logger.info("Reservation confirmed successfully: {}, order: {}",
                    reservationId, response.orderId)
                ResponseEntity.ok(response)
            }
            .onErrorResume { error ->
                logger.error("Failed to confirm reservation: {}", reservationId, error)
                handleError(error)
            }
    }

    @PostMapping("/{reservationId}/cancel")
    @Timed("reservation_cancel")
    fun cancelReservation(
        @PathVariable @NotBlank reservationId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String
    ): Mono<ResponseEntity<CancelReservationResponse>> {
        logger.info("Received reservation cancellation request: {}", reservationId)

        val cancelRequest = CancelReservationRequest(reservationId = reservationId)

        return reservationService.cancelReservation(cancelRequest)
            .map { response ->
                logger.info("Reservation cancelled successfully: {}", reservationId)
                ResponseEntity.ok(response)
            }
            .onErrorResume { error ->
                logger.error("Failed to cancel reservation: {}", reservationId, error)
                handleError(error)
            }
    }

    @GetMapping("/{reservationId}")
    @Timed("reservation_get")
    fun getReservation(
        @PathVariable @NotBlank reservationId: String
    ): Mono<ResponseEntity<ReservationResponse>> {
        logger.debug("Received reservation query: {}", reservationId)

        return reservationService.getReservation(reservationId)
            .map { response ->
                logger.debug("Reservation retrieved: {}", reservationId)
                ResponseEntity.ok(response)
            }
            .onErrorResume { error ->
                logger.error("Failed to get reservation: {}", reservationId, error)
                handleError(error)
            }
    }

    private fun <T> handleError(error: Throwable): Mono<ResponseEntity<T>> {
        val errorResponse = when (error) {
            is IllegalArgumentException -> {
                when {
                    error.message?.contains("not found") == true ->
                        ErrorResponse(ErrorDetail(ErrorCodes.VALIDATION_ERROR, error.message!!))
                    error.message?.contains("Idempotency conflict") == true ->
                        ErrorResponse(ErrorDetail(ErrorCodes.IDEMPOTENCY_CONFLICT, error.message!!))
                    else ->
                        ErrorResponse(ErrorDetail(ErrorCodes.VALIDATION_ERROR, error.message!!))
                }
            }
            is IllegalStateException -> {
                when {
                    error.message?.contains("cannot be confirmed") == true ->
                        ErrorResponse(ErrorDetail(ErrorCodes.RESERVATION_EXPIRED, error.message!!))
                    error.message?.contains("cannot be cancelled") == true ->
                        ErrorResponse(ErrorDetail(ErrorCodes.RESERVATION_EXPIRED, error.message!!))
                    error.message?.contains("not available") == true ->
                        ErrorResponse(ErrorDetail(ErrorCodes.INVENTORY_CONFLICT, error.message!!))
                    else ->
                        ErrorResponse(ErrorDetail(ErrorCodes.VALIDATION_ERROR, error.message!!))
                }
            }
            is RuntimeException -> {
                when {
                    error.message?.contains("Failed to commit") == true ->
                        ErrorResponse(ErrorDetail(ErrorCodes.PAYMENT_NOT_APPROVED, error.message!!))
                    else ->
                        ErrorResponse(ErrorDetail(ErrorCodes.INTERNAL_ERROR, "Internal server error"))
                }
            }
            else -> {
                logger.error("Unexpected error", error)
                ErrorResponse(ErrorDetail(ErrorCodes.INTERNAL_ERROR, "Internal server error"))
            }
        }

        val status = when (errorResponse.error.code) {
            ErrorCodes.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
            ErrorCodes.FORBIDDEN -> HttpStatus.FORBIDDEN
            ErrorCodes.VALIDATION_ERROR, ErrorCodes.IDEMPOTENCY_CONFLICT -> HttpStatus.BAD_REQUEST
            ErrorCodes.RESERVATION_EXPIRED -> HttpStatus.CONFLICT
            ErrorCodes.INVENTORY_CONFLICT -> HttpStatus.CONFLICT
            ErrorCodes.PAYMENT_NOT_APPROVED -> HttpStatus.PRECONDITION_FAILED
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }

        return Mono.just(ResponseEntity.status(status).body(null))
    }
}
