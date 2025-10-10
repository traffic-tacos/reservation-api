package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.OutboxEvent
import com.traffictacos.reservation.domain.OutboxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional

@Repository
class OutboxRepository(
    private val outboxTable: DynamoDbTable<OutboxEvent>
) {

    fun save(outboxEvent: OutboxEvent): Mono<OutboxEvent> = mono {
        outboxTable.putItem(outboxEvent)
        outboxEvent
    }

    fun findById(outboxId: String): Mono<OutboxEvent?> = mono {
        val key = Key.builder()
            .partitionValue(outboxId)
            .build()

        outboxTable.getItem(key)
    }

    fun findPendingEvents(): Flux<OutboxEvent> = Flux.create { sink ->
        try {
            // Simplified scan - get all events and filter in code for now
            val scan = ScanEnhancedRequest.builder().build()

            outboxTable.scan(scan).items()
                .filter { it.status == OutboxStatus.PENDING }
                .forEach { event ->
                    sink.next(event)
                }
            sink.complete()
        } catch (e: Exception) {
            sink.error(e)
        }
    }

    /**
     * 비동기적으로 Outbox 이벤트를 저장합니다.
     * 
     * Dispatchers.IO에서 실행하여 WebFlux Event Loop 스레드를 블로킹하지 않습니다.
     */
    suspend fun saveAsync(outboxEvent: OutboxEvent): OutboxEvent = withContext(Dispatchers.IO) {
        outboxTable.putItem(outboxEvent)
        outboxEvent
    }

    /**
     * 비동기적으로 Outbox 이벤트를 조회합니다.
     * 
     * Dispatchers.IO에서 실행하여 WebFlux Event Loop 스레드를 블로킹하지 않습니다.
     */
    suspend fun findByIdAsync(outboxId: String): OutboxEvent? = withContext(Dispatchers.IO) {
        val key = Key.builder()
            .partitionValue(outboxId)
            .build()

        outboxTable.getItem(key)
    }
}