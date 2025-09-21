package com.traffictacos.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.traffictacos.reservation.dto.ErrorCode
import com.traffictacos.reservation.repository.IdempotencyRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class IdempotencyService(
    private val idempotencyRepository: IdempotencyRepository,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(IdempotencyService::class.java)

    suspend fun <T : Any, R : Any> executeIdempotent(
        idempotencyKey: String,
        request: T,
        operation: suspend () -> R
    ): R {
        logger.debug("Executing idempotent operation with key: {}", idempotencyKey)

        // Create request hash
        val requestJson = objectMapper.writeValueAsString(request)
        val requestHash = createHash(requestJson)

        // Check for existing idempotency record
        val existingRecord = idempotencyRepository.findByKeyAsync(idempotencyKey)

        if (existingRecord != null) {
            logger.debug("Found existing idempotency record for key: {}", idempotencyKey)

            // Check if request is identical
            if (existingRecord.requestHash == requestHash) {
                // Same request, return cached response
                logger.info("Returning cached response for idempotency key: {}", idempotencyKey)
                @Suppress("UNCHECKED_CAST")
                return objectMapper.readValue(existingRecord.responseSnapshot, Any::class.java) as R
            } else {
                // Different request with same idempotency key
                logger.warn("Idempotency conflict for key: {}", idempotencyKey)
                throw ReservationException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Request with same idempotency key but different content already exists"
                )
            }
        }

        // Execute operation
        val result = operation()

        // Store result for future idempotency checks
        val responseSnapshot = objectMapper.writeValueAsString(result)
        idempotencyRepository.saveWithTtl(idempotencyKey, requestHash, responseSnapshot)

        logger.debug("Stored idempotency record for key: {}", idempotencyKey)

        return result
    }

    private fun createHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}