package com.traffictacos.reservation.controller

import com.traffictacos.reservation.dto.ErrorResponse
import com.traffictacos.reservation.dto.ErrorDetail
import com.traffictacos.reservation.dto.ErrorCode
import com.traffictacos.reservation.service.ReservationException
// import io.micrometer.tracing.TraceContext
// import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.FieldError
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ReservationException::class)
    fun handleReservationException(ex: ReservationException): ResponseEntity<ErrorResponse> {
        val traceId = "local-trace-${System.currentTimeMillis()}"
        logger.warn("Reservation exception: {} - {}", ex.errorCode, ex.message, ex)

        val status = when (ex.errorCode) {
            ErrorCode.RESERVATION_NOT_FOUND -> HttpStatus.NOT_FOUND
            ErrorCode.INVALID_REQUEST,
            ErrorCode.MISSING_IDEMPOTENCY_KEY,
            ErrorCode.RESERVATION_EXPIRED,
            ErrorCode.RESERVATION_ALREADY_CONFIRMED,
            ErrorCode.RESERVATION_ALREADY_CANCELLED,
            ErrorCode.INVALID_RESERVATION_TOKEN -> HttpStatus.BAD_REQUEST
            ErrorCode.SEAT_UNAVAILABLE,
            ErrorCode.IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT
            ErrorCode.PAYMENT_FAILED,
            ErrorCode.PAYMENT_NOT_CONFIRMED -> HttpStatus.PAYMENT_REQUIRED
            ErrorCode.RATE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS
            ErrorCode.INVENTORY_SERVICE_ERROR,
            ErrorCode.SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }

        val errorResponse = ErrorResponse(
            error = ErrorDetail(
                code = ex.errorCode.code,
                message = ex.message,
                traceId = traceId
            )
        )

        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationException(ex: WebExchangeBindException): ResponseEntity<ErrorResponse> {
        val traceId = "local-trace-${System.currentTimeMillis()}"
        logger.warn("Validation exception: {}", ex.message)

        val fieldErrors = ex.bindingResult.fieldErrors.associate { fieldError: FieldError ->
            fieldError.field to (fieldError.defaultMessage ?: "Invalid value")
        }

        val errorResponse = ErrorResponse(
            error = ErrorDetail(
                code = ErrorCode.INVALID_REQUEST.code,
                message = "Validation failed",
                traceId = traceId,
                details = fieldErrors
            )
        )

        return ResponseEntity.badRequest().body(errorResponse)
    }

    @ExceptionHandler(ServerWebInputException::class)
    fun handleServerWebInputException(ex: ServerWebInputException): ResponseEntity<ErrorResponse> {
        val traceId = "local-trace-${System.currentTimeMillis()}"
        logger.warn("Invalid input exception: {}", ex.message)

        val errorResponse = ErrorResponse(
            error = ErrorDetail(
                code = ErrorCode.INVALID_REQUEST.code,
                message = "Invalid request format",
                traceId = traceId
            )
        )

        return ResponseEntity.badRequest().body(errorResponse)
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(ex: AuthenticationException): ResponseEntity<ErrorResponse> {
        val traceId = "local-trace-${System.currentTimeMillis()}"
        logger.warn("Authentication exception: {}", ex.message)

        val errorResponse = ErrorResponse(
            error = ErrorDetail(
                code = "AUTHENTICATION_FAILED",
                message = "Authentication failed",
                traceId = traceId
            )
        )

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        val traceId = "local-trace-${System.currentTimeMillis()}"
        logger.warn("Illegal argument exception: {}", ex.message)

        val errorResponse = ErrorResponse(
            error = ErrorDetail(
                code = ErrorCode.INVALID_REQUEST.code,
                message = ex.message ?: "Invalid request",
                traceId = traceId
            )
        )

        return ResponseEntity.badRequest().body(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        val traceId = "local-trace-${System.currentTimeMillis()}"
        logger.error("Unexpected exception: {}", ex.message, ex)

        val errorResponse = ErrorResponse(
            error = ErrorDetail(
                code = ErrorCode.INTERNAL_ERROR.code,
                message = "An unexpected error occurred",
                traceId = traceId
            )
        )

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }

}