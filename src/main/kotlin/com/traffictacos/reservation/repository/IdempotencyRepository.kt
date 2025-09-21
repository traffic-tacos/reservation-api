package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Idempotency
import kotlinx.coroutines.reactor.mono
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.Key
import java.time.Instant

@Repository
class IdempotencyRepository(
    private val idempotencyTable: DynamoDbTable<Idempotency>
) {

    fun save(idempotency: Idempotency): Mono<Idempotency> = mono {
        idempotencyTable.putItem(idempotency)
        idempotency
    }

    fun findByKey(idempotencyKey: String): Mono<Idempotency?> = mono {
        val key = Key.builder()
            .partitionValue(idempotencyKey)
            .build()

        idempotencyTable.getItem(key)
    }

    suspend fun saveWithTtl(idempotencyKey: String, requestHash: String, responseSnapshot: String): Idempotency {
        val ttl = Instant.now().plusSeconds(300).epochSecond // 5 minutes TTL
        val idempotency = Idempotency(
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            responseSnapshot = responseSnapshot,
            ttl = ttl
        )

        idempotencyTable.putItem(idempotency)
        return idempotency
    }

    suspend fun findByKeyAsync(idempotencyKey: String): Idempotency? {
        val key = Key.builder()
            .partitionValue(idempotencyKey)
            .build()

        return idempotencyTable.getItem(key)
    }
}