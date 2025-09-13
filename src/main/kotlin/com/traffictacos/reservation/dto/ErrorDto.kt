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

// Error Codes
object ErrorCodes {
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val FORBIDDEN = "FORBIDDEN"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val IDEMPOTENCY_REQUIRED = "IDEMPOTENCY_REQUIRED"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val RESERVATION_EXPIRED = "RESERVATION_EXPIRED"
    const val PAYMENT_NOT_APPROVED = "PAYMENT_NOT_APPROVED"
    const val INVENTORY_CONFLICT = "INVENTORY_CONFLICT"
    const val UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
