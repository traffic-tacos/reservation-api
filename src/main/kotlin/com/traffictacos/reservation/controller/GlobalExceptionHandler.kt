package com.traffictacos.reservation.controller

import com.traffictacos.reservation.dto.ErrorCodes
import com.traffictacos.reservation.dto.ErrorDetail
import com.traffictacos.reservation.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono

@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationException(ex: WebExchangeBindException): Mono<ResponseEntity<ErrorResponse>> {
        val errors = ex.bindingResult.allErrors
        val errorMessage = if (errors.isNotEmpty()) {
            val fieldError = errors[0] as? FieldError
            fieldError?.defaultMessage ?: "Validation failed"
        } else {
            "Validation failed"
        }

        logger.warn("Validation error: {}", errorMessage)

        val errorResponse = ErrorResponse(
            ErrorDetail(ErrorCodes.VALIDATION_ERROR, errorMessage)
        )

        return Mono.just(ResponseEntity.badRequest().body(errorResponse))
    }

    @ExceptionHandler(ServerWebInputException::class)
    fun handleServerWebInputException(ex: ServerWebInputException): Mono<ResponseEntity<ErrorResponse>> {
        logger.warn("Request input error: {}", ex.message)

        val errorResponse = ErrorResponse(
            ErrorDetail(ErrorCodes.VALIDATION_ERROR, "Invalid request format")
        )

        return Mono.just(ResponseEntity.badRequest().body(errorResponse))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): Mono<ResponseEntity<ErrorResponse>> {
        logger.warn("Illegal argument: {}", ex.message)

        val errorCode = when {
            ex.message?.contains("Idempotency-Key") == true -> ErrorCodes.IDEMPOTENCY_REQUIRED
            else -> ErrorCodes.VALIDATION_ERROR
        }

        val errorResponse = ErrorResponse(
            ErrorDetail(errorCode, ex.message ?: "Invalid argument")
        )

        return Mono.just(ResponseEntity.badRequest().body(errorResponse))
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): Mono<ResponseEntity<ErrorResponse>> {
        logger.error("Unexpected error", ex)

        val errorResponse = ErrorResponse(
            ErrorDetail(ErrorCodes.INTERNAL_ERROR, "Internal server error")
        )

        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse))
    }
}
