package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Idempotency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
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

    /**
     * TTL과 함께 멱등성 레코드를 저장합니다 (5분 TTL).
     * 
     * Dispatchers.IO에서 실행하여 WebFlux Event Loop 스레드를 블로킹하지 않습니다.
     */
    suspend fun saveWithTtl(
        idempotencyKey: String,
        requestHash: String,
        responseSnapshot: String
    ): Idempotency = withContext(Dispatchers.IO) {
        val ttl = Instant.now().plusSeconds(300).epochSecond // 5 minutes TTL
        val idempotency = Idempotency(
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            responseSnapshot = responseSnapshot,
            ttl = ttl
        )

        idempotencyTable.putItem(idempotency)
        idempotency
    }

    /**
     * 멱등성 키로 레코드를 조회합니다.
     * 
     * Dispatchers.IO에서 실행하여 WebFlux Event Loop 스레드를 블로킹하지 않습니다.
     */
    suspend fun findByKeyAsync(idempotencyKey: String): Idempotency? = withContext(Dispatchers.IO) {
        val key = Key.builder()
            .partitionValue(idempotencyKey)
            .build()

        idempotencyTable.getItem(key)
    }
}