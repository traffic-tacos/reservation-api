package com.traffictacos.reservation.dto

import com.traffictacos.reservation.domain.ReservationStatus
import jakarta.validation.constraints.*
import java.time.Instant

// Request DTOs
data class CreateReservationRequest(
    @field:NotBlank(message = "Event ID is required")
    val eventId: String,

    @field:Min(value = 1, message = "Quantity must be at least 1")
    @field:Max(value = 10, message = "Quantity cannot exceed 10")
    val quantity: Int,

    val seatIds: List<String> = emptyList(),

    @field:NotBlank(message = "Reservation token is required")
    val reservationToken: String
)

data class ConfirmReservationRequest(
    @field:NotBlank(message = "Reservation ID is required")
    val reservationId: String,

    @field:NotBlank(message = "Payment intent ID is required")
    val paymentIntentId: String
)

data class CancelReservationRequest(
    @field:NotBlank(message = "Reservation ID is required")
    val reservationId: String
)

// Response DTOs
data class CreateReservationResponse(
    val reservationId: String,
    val status: ReservationStatus,
    val holdExpiresAt: Instant,
    val message: String? = null
)

data class ReservationDetailsResponse(
    val reservationId: String,
    val eventId: String,
    val quantity: Int,
    val seatIds: List<String>,
    val status: ReservationStatus,
    val holdExpiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ConfirmReservationResponse(
    val orderId: String,
    val reservationId: String,
    val status: ReservationStatus,
    val message: String? = null
)

data class CancelReservationResponse(
    val reservationId: String,
    val status: ReservationStatus,
    val message: String? = null
)