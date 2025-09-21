package com.traffictacos.reservation.dto

data class ErrorResponse(
    val error: ErrorDetail
)

data class ErrorDetail(
    val code: String,
    val message: String,
    val traceId: String? = null,
    val details: Map<String, Any>? = null
)

enum class ErrorCode(val code: String, val defaultMessage: String) {
    // Validation errors
    INVALID_REQUEST("INVALID_REQUEST", "Invalid request parameters"),
    MISSING_IDEMPOTENCY_KEY("MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required"),

    // Business logic errors
    RESERVATION_NOT_FOUND("RESERVATION_NOT_FOUND", "Reservation not found"),
    RESERVATION_EXPIRED("RESERVATION_EXPIRED", "Reservation has expired"),
    RESERVATION_ALREADY_CONFIRMED("RESERVATION_ALREADY_CONFIRMED", "Reservation is already confirmed"),
    RESERVATION_ALREADY_CANCELLED("RESERVATION_ALREADY_CANCELLED", "Reservation is already cancelled"),
    INVALID_RESERVATION_TOKEN("INVALID_RESERVATION_TOKEN", "Invalid reservation token"),

    // Inventory errors
    SEAT_UNAVAILABLE("SEAT_UNAVAILABLE", "Requested seats are not available"),
    INVENTORY_SERVICE_ERROR("INVENTORY_SERVICE_ERROR", "Inventory service is temporarily unavailable"),

    // Payment errors
    PAYMENT_FAILED("PAYMENT_FAILED", "Payment processing failed"),
    PAYMENT_NOT_CONFIRMED("PAYMENT_NOT_CONFIRMED", "Payment is not confirmed"),

    // System errors
    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error occurred"),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "Service is temporarily unavailable"),
    RATE_LIMIT_EXCEEDED("RATE_LIMIT_EXCEEDED", "Rate limit exceeded"),

    // Idempotency errors
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "Request conflicts with previous request")
}