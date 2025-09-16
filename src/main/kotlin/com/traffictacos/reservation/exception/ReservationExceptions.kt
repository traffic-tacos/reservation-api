package com.traffictacos.reservation.exception

/**
 * Base exception for all reservation-related errors
 */
sealed class ReservationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Exception thrown when a reservation is not found
 */
class ReservationNotFoundException(message: String) : ReservationException(message)

/**
 * Exception thrown when a reservation has expired
 */
class ReservationExpiredException(message: String) : ReservationException(message)

/**
 * Exception thrown when a reservation is in an invalid state for the requested operation
 */
class InvalidReservationStateException(message: String) : ReservationException(message)

/**
 * Exception thrown when inventory is not available or conflicts occur
 */
class InventoryConflictException(message: String) : ReservationException(message)

/**
 * Exception thrown when access is forbidden (e.g., wrong user accessing reservation)
 */
class ForbiddenException(message: String) : ReservationException(message)

/**
 * Exception thrown when idempotency key is required but missing
 */
class IdempotencyRequiredException(message: String) : ReservationException(message)

/**
 * Exception thrown when same idempotency key is used with different request body
 */
class IdempotencyConflictException(message: String) : ReservationException(message)

/**
 * Exception thrown when payment is not approved
 */
class PaymentNotApprovedException(message: String) : ReservationException(message)

/**
 * Exception thrown when upstream service (inventory, payment) times out
 */
class UpstreamTimeoutException(message: String, cause: Throwable? = null) : ReservationException(message, cause)

/**
 * Exception thrown when rate limit is exceeded
 */
class RateLimitExceededException(message: String) : ReservationException(message)

/**
 * Exception thrown for validation errors
 */
class ValidationException(message: String) : ReservationException(message)