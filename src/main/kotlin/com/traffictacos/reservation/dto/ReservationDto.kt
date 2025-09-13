package com.traffictacos.reservation.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Instant

// Request DTOs
data class CreateReservationRequest(
    @field:NotBlank(message = "Event ID is required")
    val eventId: String,

    @field:NotNull(message = "Quantity is required")
    @field:Positive(message = "Quantity must be positive")
    val qty: Int,

    @field:NotEmpty(message = "Seat IDs are required")
    val seatIds: List<String>,

    @field:NotBlank(message = "Reservation token is required")
    val reservationToken: String,

    @field:NotBlank(message = "User ID is required")
    val userId: String
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
data class ReservationResponse(
    val reservationId: String,
    val eventId: String,
    val userId: String,
    val qty: Int,
    val seatIds: List<String>,
    val status: ReservationStatus,
    val holdExpiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CreateReservationResponse(
    val reservationId: String,
    val holdExpiresAt: Instant
)

data class OrderResponse(
    val orderId: String,
    val reservationId: String,
    val eventId: String,
    val userId: String,
    val seatIds: List<String>,
    val totalAmount: Long,
    val status: OrderStatus,
    val createdAt: Instant
)

data class ConfirmReservationResponse(
    val orderId: String,
    val status: OrderStatus
)

// Enums
enum class ReservationStatus {
    HOLD,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}

enum class OrderStatus {
    CONFIRMED,
    CANCELLED,
    REFUNDED
}
