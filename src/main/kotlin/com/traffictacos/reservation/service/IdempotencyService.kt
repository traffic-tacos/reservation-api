package com.traffictacos.reservation.service

import com.traffictacos.reservation.repository.IdempotencyRepository
import com.traffictacos.reservation.domain.IdempotencyRecord
import com.traffictacos.reservation.exception.IdempotencyConflictException
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Instant
import java.time.Duration
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

@Service
class IdempotencyService(
    private val idempotencyRepository: IdempotencyRepository,
    private val objectMapper: ObjectMapper
) {
    companion object {
        private val IDEMPOTENCY_TTL = Duration.ofMinutes(5)
    }

    fun <T> executeIdempotent(idempotencyKey: String, operation: () -> Mono<T>): Mono<T> {
        return idempotencyRepository.findById(idempotencyKey)
            .flatMap { existingRecord ->
                if (existingRecord.isExpired()) {
                    // Record expired, delete and execute operation
                    idempotencyRepository.deleteById(idempotencyKey)
                        .then(executeAndStore(idempotencyKey, operation))
                } else {
                    // Return cached response
                    logger.debug { "Returning cached response for idempotency key: $idempotencyKey" }
                    try {
                        val cachedResponse = objectMapper.readValue(existingRecord.responseSnapshot, Any::class.java) as T
                        Mono.just(cachedResponse)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to deserialize cached response for key: $idempotencyKey" }
                        Mono.error(e)
                    }
                }
            }
            .switchIfEmpty(
                executeAndStore(idempotencyKey, operation)
            )
    }

    fun <T> executeIdempotentWithRequestValidation(
        idempotencyKey: String,
        requestBody: Any,
        operation: () -> Mono<T>
    ): Mono<T> {
        val requestHash = calculateRequestHash(requestBody)
        
        return idempotencyRepository.findById(idempotencyKey)
            .flatMap { existingRecord ->
                if (existingRecord.isExpired()) {
                    // Record expired, delete and execute operation
                    idempotencyRepository.deleteById(idempotencyKey)
                        .then(executeAndStoreWithValidation(idempotencyKey, requestHash, operation))
                } else if (existingRecord.requestHash != requestHash) {
                    // Same idempotency key with different request - conflict
                    logger.warn { "Idempotency conflict for key: $idempotencyKey" }
                    Mono.error(IdempotencyConflictException("Same idempotency key used with different request body"))
                } else {
                    // Return cached response
                    logger.debug { "Returning cached response for idempotency key: $idempotencyKey" }
                    try {
                        val cachedResponse = objectMapper.readValue(existingRecord.responseSnapshot, Any::class.java) as T
                        Mono.just(cachedResponse)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to deserialize cached response for key: $idempotencyKey" }
                        Mono.error(e)
                    }
                }
            }
            .switchIfEmpty(
                executeAndStoreWithValidation(idempotencyKey, requestHash, operation)
            )
    }

    private fun <T> executeAndStore(idempotencyKey: String, operation: () -> Mono<T>): Mono<T> {
        return operation()
            .flatMap { result ->
                try {
                    val responseSnapshot = objectMapper.writeValueAsString(result)
                    val record = IdempotencyRecord(
                        idempotencyKey = idempotencyKey,
                        requestHash = "",
                        responseSnapshot = responseSnapshot,
                        ttl = Instant.now().plus(IDEMPOTENCY_TTL)
                    )
                    
                    idempotencyRepository.save(record)
                        .then(Mono.just(result))
                        .doOnSuccess { 
                            logger.debug { "Stored idempotency record for key: $idempotencyKey" }
                        }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to serialize response for idempotency key: $idempotencyKey" }
                    Mono.just(result) // Return result without caching
                }
            }
    }

    private fun <T> executeAndStoreWithValidation(
        idempotencyKey: String,
        requestHash: String,
        operation: () -> Mono<T>
    ): Mono<T> {
        return operation()
            .flatMap { result ->
                try {
                    val responseSnapshot = objectMapper.writeValueAsString(result)
                    val record = IdempotencyRecord(
                        idempotencyKey = idempotencyKey,
                        requestHash = requestHash,
                        responseSnapshot = responseSnapshot,
                        ttl = Instant.now().plus(IDEMPOTENCY_TTL)
                    )
                    
                    idempotencyRepository.save(record)
                        .then(Mono.just(result))
                        .doOnSuccess { 
                            logger.debug { "Stored idempotency record with validation for key: $idempotencyKey" }
                        }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to serialize response for idempotency key: $idempotencyKey" }
                    Mono.just(result) // Return result without caching
                }
            }
    }

    private fun calculateRequestHash(requestBody: Any): String {
        return try {
            val requestJson = objectMapper.writeValueAsString(requestBody)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(requestJson.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to calculate request hash" }
            requestBody.hashCode().toString()
        }
    }
}