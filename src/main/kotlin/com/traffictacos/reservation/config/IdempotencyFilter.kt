package com.traffictacos.reservation.config

import com.traffictacos.reservation.dto.ErrorCodes
import com.traffictacos.reservation.dto.ErrorDetail
import com.traffictacos.reservation.dto.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Profile
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.*

@Component
@Profile("!local")
class IdempotencyFilter(
    private val objectMapper: ObjectMapper
) : WebFilter {
    private val logger = LoggerFactory.getLogger(IdempotencyFilter::class.java)

    // Paths that require idempotency key
    private val idempotentPaths = setOf(
        "/v1/reservations",
        "/v1/reservations/confirm",
        "/v1/reservations/cancel"
    )

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        val method = exchange.request.method.name()

        // Check if this path requires idempotency
        val requiresIdempotency = when {
            method == "POST" && path == "/v1/reservations" -> true
            method == "POST" && path.matches(Regex("/v1/reservations/[^/]+/confirm")) -> true
            method == "POST" && path.matches(Regex("/v1/reservations/[^/]+/cancel")) -> true
            else -> false
        }

        if (requiresIdempotency) {
            val idempotencyKey = exchange.request.headers.getFirst("Idempotency-Key")

            if (idempotencyKey.isNullOrBlank()) {
                logger.warn("Missing Idempotency-Key header for path: {}", path)

                val errorResponse = ErrorResponse(
                    ErrorDetail(ErrorCodes.IDEMPOTENCY_REQUIRED, "Idempotency-Key header is required")
                )

                return exchange.response.let { response ->
                    response.statusCode = HttpStatus.BAD_REQUEST
                    response.headers.contentType = MediaType.APPLICATION_JSON
                    val buffer = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(errorResponse))
                    response.writeWith(Mono.just(buffer))
                }
            }
        }

        return chain.filter(exchange)
    }
}