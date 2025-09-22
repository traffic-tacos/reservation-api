package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.OutboxEvent
import com.traffictacos.reservation.domain.OutboxStatus
import kotlinx.coroutines.reactor.mono
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

    suspend fun saveAsync(outboxEvent: OutboxEvent): OutboxEvent {
        outboxTable.putItem(outboxEvent)
        return outboxEvent
    }

    suspend fun findByIdAsync(outboxId: String): OutboxEvent? {
        val key = Key.builder()
            .partitionValue(outboxId)
            .build()

        return outboxTable.getItem(key)
    }
}