package com.traffictacos.reservation.controller

import com.traffictacos.reservation.dto.*
import com.traffictacos.reservation.service.ReservationService
import com.traffictacos.reservation.exception.IdempotencyRequiredException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import io.micrometer.core.instrument.MeterRegistry
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1/reservations")
class ReservationController(
    private val reservationService: ReservationService,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val API_REQUEST_COUNTER = "api_requests_total"
    }

    @PostMapping
    fun createReservation(
        @Valid @RequestBody request: CreateReservationRequest,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyKey: String?,
        authentication: Authentication
    ): Mono<ResponseEntity<CreateReservationResponse>> {
        meterRegistry.counter(API_REQUEST_COUNTER, "endpoint", "create_reservation", "method", "POST").increment()
        
        if (idempotencyKey.isNullOrBlank()) {
            return Mono.error(IdempotencyRequiredException("Idempotency-Key header is required for this operation"))
        }
        
        val userId = extractUserId(authentication)
        logger.info { "Creating reservation for user=$userId with idempotency key=$idempotencyKey" }
        
        return reservationService.createReservation(request, userId, idempotencyKey)
            .map { response ->
                ResponseEntity.status(HttpStatus.CREATED).body(response)
            }
    }

    @GetMapping("/{reservationId}")
    fun getReservation(
        @PathVariable reservationId: String,
        authentication: Authentication
    ): Mono<ResponseEntity<ReservationResponse>> {
        meterRegistry.counter(API_REQUEST_COUNTER, "endpoint", "get_reservation", "method", "GET").increment()
        
        val userId = extractUserId(authentication)
        logger.debug { "Getting reservation $reservationId for user=$userId" }
        
        return reservationService.getReservation(reservationId)
            .map { response ->
                // Check if user owns this reservation
                if (response.userId != userId) {
                    ResponseEntity.status(HttpStatus.FORBIDDEN).build<ReservationResponse>()
                } else {
                    ResponseEntity.ok(response)
                }
            }
    }

    @PostMapping("/{reservationId}/confirm")
    fun confirmReservation(
        @PathVariable reservationId: String,
        @Valid @RequestBody request: ConfirmReservationRequest,
        authentication: Authentication
    ): Mono<ResponseEntity<ConfirmReservationResponse>> {
        meterRegistry.counter(API_REQUEST_COUNTER, "endpoint", "confirm_reservation", "method", "POST").increment()
        
        val userId = extractUserId(authentication)
        logger.info { "Confirming reservation $reservationId for user=$userId" }
        
        return reservationService.confirmReservation(reservationId, request, userId)
            .map { response ->
                ResponseEntity.ok(response)
            }
    }

    @PostMapping("/{reservationId}/cancel")
    fun cancelReservation(
        @PathVariable reservationId: String,
        authentication: Authentication
    ): Mono<ResponseEntity<CancelReservationResponse>> {
        meterRegistry.counter(API_REQUEST_COUNTER, "endpoint", "cancel_reservation", "method", "POST").increment()
        
        val userId = extractUserId(authentication)
        logger.info { "Cancelling reservation $reservationId for user=$userId" }
        
        return reservationService.cancelReservation(reservationId, userId)
            .map { response ->
                ResponseEntity.ok(response)
            }
    }

    private fun extractUserId(authentication: Authentication): String {
        // Extract user ID from JWT token claims
        // This assumes the JWT contains a 'sub' claim with the user ID
        return authentication.name ?: "anonymous"
    }
}