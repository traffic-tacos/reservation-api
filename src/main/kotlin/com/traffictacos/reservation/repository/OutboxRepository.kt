package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.EventType
import com.traffictacos.reservation.domain.OutboxEvent
import com.traffictacos.reservation.domain.OutboxStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Repository
class OutboxRepository(
    private val dynamoDbAsyncClient: DynamoDbAsyncClient,
    @Value("\${app.dynamodb.tables.outbox}") private val tableName: String
) {

    fun save(event: OutboxEvent): Mono<OutboxEvent> {
        logger.debug { "Saving outbox event: ${event.outboxId}" }
        
        val item = mutableMapOf<String, AttributeValue>()

        // Primary Key
        item[OutboxEvent.PK_NAME] = AttributeValue.builder().s(event.outboxId).build()
        item[OutboxEvent.SK_NAME] = AttributeValue.builder().s("${event.aggregateType}#${event.aggregateId}").build()

        // Attributes
        item[OutboxEvent.TYPE] = AttributeValue.builder().s(event.type.name).build()
        item[OutboxEvent.AGGREGATE_TYPE] = AttributeValue.builder().s(event.aggregateType).build()
        item[OutboxEvent.AGGREGATE_ID] = AttributeValue.builder().s(event.aggregateId).build()
        item[OutboxEvent.PAYLOAD] = AttributeValue.builder().s(event.payload).build()
        item[OutboxEvent.STATUS] = AttributeValue.builder().s(event.status.name).build()
        item[OutboxEvent.ATTEMPTS] = AttributeValue.builder().n(event.attempts.toString()).build()

        event.nextRetryAt?.let {
            item[OutboxEvent.NEXT_RETRY_AT] = AttributeValue.builder().s(it.toString()).build()
        }

        event.lastError?.let {
            item[OutboxEvent.LAST_ERROR] = AttributeValue.builder().s(it).build()
        }

        event.traceId?.let {
            item[OutboxEvent.TRACE_ID] = AttributeValue.builder().s(it).build()
        }

        item[OutboxEvent.CREATED_AT] = AttributeValue.builder().s(event.createdAt.toString()).build()
        item[OutboxEvent.UPDATED_AT] = AttributeValue.builder().s(event.updatedAt.toString()).build()

        val request = PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.putItem(request) }
            .map { event }
            .doOnSuccess { logger.debug { "Successfully saved outbox event: ${it.outboxId}" } }
            .doOnError { error -> logger.error(error) { "Failed to save outbox event: ${event.outboxId}" } }
    }

    fun findById(outboxId: String): Mono<OutboxEvent?> {
        logger.debug { "Finding outbox event: $outboxId" }
        
        val key = mapOf(
            OutboxEvent.PK_NAME to AttributeValue.builder().s(outboxId).build()
        )

        val request = GetItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.getItem(request) }
            .map { response ->
                if (response.hasItem()) {
                    response.item().toOutboxEvent()
                } else {
                    null
                }
            }
            .doOnSuccess { event ->
                if (event != null) {
                    logger.debug { "Found outbox event: ${event.outboxId}" }
                } else {
                    logger.debug { "Outbox event not found: $outboxId" }
                }
            }
            .doOnError { error -> logger.error(error) { "Failed to find outbox event: $outboxId" } }
    }

    fun findPendingEvents(limit: Int = 10): reactor.core.publisher.Flux<OutboxEvent> {
        logger.debug { "Finding pending outbox events (limit: $limit)" }
        
        val now = Instant.now()

        // Query by GSI for pending events
        val request = QueryRequest.builder()
            .tableName(tableName)
            .indexName(OutboxEvent.GSI_STATUS_NAME)
            .keyConditionExpression("#status = :status")
            .filterExpression("(#nextRetryAt <= :now OR attribute_not_exists(#nextRetryAt)) AND #attempts < :maxAttempts")
            .expressionAttributeNames(
                mapOf(
                    "#status" to OutboxEvent.STATUS,
                    "#nextRetryAt" to OutboxEvent.NEXT_RETRY_AT,
                    "#attempts" to OutboxEvent.ATTEMPTS
                )
            )
            .expressionAttributeValues(
                mapOf(
                    ":status" to AttributeValue.builder().s(OutboxStatus.PENDING.name).build(),
                    ":now" to AttributeValue.builder().s(now.toString()).build(),
                    ":maxAttempts" to AttributeValue.builder().n(OutboxEvent.MAX_RETRY_ATTEMPTS.toString()).build()
                )
            )
            .scanIndexForward(true)
            .limit(limit)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.query(request) }
            .flatMapMany { response ->
                reactor.core.publisher.Flux.fromIterable(response.items())
                    .map { it.toOutboxEvent() }
            }
            .onErrorResume { error ->
                logger.warn(error) { "Failed to query pending events, falling back to scan" }
                findPendingEventsScan(limit)
            }
            .doOnComplete { logger.debug { "Completed finding pending outbox events" } }
            .doOnError { error -> logger.error(error) { "Failed to find pending outbox events" } }
    }

    private fun findPendingEventsScan(limit: Int): reactor.core.publisher.Flux<OutboxEvent> {
        val now = Instant.now()

        val request = ScanRequest.builder()
            .tableName(tableName)
            .filterExpression("#status = :status AND (#nextRetryAt <= :now OR attribute_not_exists(#nextRetryAt)) AND #attempts < :maxAttempts")
            .expressionAttributeNames(
                mapOf(
                    "#status" to OutboxEvent.STATUS,
                    "#nextRetryAt" to OutboxEvent.NEXT_RETRY_AT,
                    "#attempts" to OutboxEvent.ATTEMPTS
                )
            )
            .expressionAttributeValues(
                mapOf(
                    ":status" to AttributeValue.builder().s(OutboxStatus.PENDING.name).build(),
                    ":now" to AttributeValue.builder().s(now.toString()).build(),
                    ":maxAttempts" to AttributeValue.builder().n(OutboxEvent.MAX_RETRY_ATTEMPTS.toString()).build()
                )
            )
            .limit(limit)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.scan(request) }
            .flatMapMany { response ->
                reactor.core.publisher.Flux.fromIterable(response.items())
                    .map { it.toOutboxEvent() }
            }
    }

    fun updateStatus(outboxId: String, status: OutboxStatus, attempts: Int = 0, nextRetryAt: Instant? = null, lastError: String? = null): Mono<OutboxEvent?> {
        logger.debug { "Updating outbox event status: $outboxId to $status" }
        
        val key = mapOf(
            OutboxEvent.PK_NAME to AttributeValue.builder().s(outboxId).build()
        )

        var updateExpression = "SET #status = :status, #attempts = :attempts, #updated = :updated"
        val expressionAttributeNames = mutableMapOf(
            "#status" to OutboxEvent.STATUS,
            "#attempts" to OutboxEvent.ATTEMPTS,
            "#updated" to OutboxEvent.UPDATED_AT
        )
        val expressionAttributeValues = mutableMapOf(
            ":status" to AttributeValue.builder().s(status.name).build(),
            ":attempts" to AttributeValue.builder().n(attempts.toString()).build(),
            ":updated" to AttributeValue.builder().s(Instant.now().toString()).build()
        )

        if (nextRetryAt != null) {
            updateExpression += ", #nextRetryAt = :nextRetryAt"
            expressionAttributeNames["#nextRetryAt"] = OutboxEvent.NEXT_RETRY_AT
            expressionAttributeValues[":nextRetryAt"] = AttributeValue.builder().s(nextRetryAt.toString()).build()
        }

        if (lastError != null) {
            updateExpression += ", #lastError = :lastError"
            expressionAttributeNames["#lastError"] = OutboxEvent.LAST_ERROR
            expressionAttributeValues[":lastError"] = AttributeValue.builder().s(lastError).build()
        }

        val request = UpdateItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .updateExpression(updateExpression)
            .expressionAttributeNames(expressionAttributeNames)
            .expressionAttributeValues(expressionAttributeValues)
            .returnValues(ReturnValue.ALL_NEW)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.updateItem(request) }
            .map { response -> response.attributes().toOutboxEvent() }
            .doOnSuccess { event -> logger.debug { "Successfully updated outbox event status: ${event.outboxId}" } }
            .doOnError { error -> logger.error(error) { "Failed to update outbox event status: $outboxId" } }
    }

    fun deleteById(outboxId: String): Mono<Unit> {
        logger.debug { "Deleting outbox event: $outboxId" }
        
        val key = mapOf(
            OutboxEvent.PK_NAME to AttributeValue.builder().s(outboxId).build()
        )

        val request = DeleteItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.deleteItem(request) }
            .map { Unit }
            .doOnSuccess { logger.debug { "Successfully deleted outbox event: $outboxId" } }
            .doOnError { error -> logger.error(error) { "Failed to delete outbox event: $outboxId" } }
    }

    fun findRetryableEvents(): reactor.core.publisher.Flux<OutboxEvent> {
        logger.debug { "Finding retryable outbox events" }
        
        val now = Instant.now()

        val request = ScanRequest.builder()
            .tableName(tableName)
            .filterExpression("#status = :status AND #nextRetryAt <= :now AND #attempts < :maxAttempts")
            .expressionAttributeNames(
                mapOf(
                    "#status" to OutboxEvent.STATUS,
                    "#nextRetryAt" to OutboxEvent.NEXT_RETRY_AT,
                    "#attempts" to OutboxEvent.ATTEMPTS
                )
            )
            .expressionAttributeValues(
                mapOf(
                    ":status" to AttributeValue.builder().s(OutboxStatus.FAILED.name).build(),
                    ":now" to AttributeValue.builder().s(now.toString()).build(),
                    ":maxAttempts" to AttributeValue.builder().n(OutboxEvent.MAX_RETRY_ATTEMPTS.toString()).build()
                )
            )
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.scan(request) }
            .flatMapMany { response ->
                reactor.core.publisher.Flux.fromIterable(response.items())
                    .map { it.toOutboxEvent() }
            }
            .doOnComplete { logger.debug { "Completed finding retryable outbox events" } }
            .doOnError { error -> logger.error(error) { "Failed to find retryable outbox events" } }
    }

    private fun Map<String, AttributeValue>.toOutboxEvent(): OutboxEvent {
        return OutboxEvent(
            outboxId = this[OutboxEvent.PK_NAME]?.s() ?: "",
            type = EventType.valueOf(this[OutboxEvent.TYPE]?.s() ?: "RESERVATION_CREATED"),
            aggregateType = this[OutboxEvent.AGGREGATE_TYPE]?.s() ?: "",
            aggregateId = this[OutboxEvent.AGGREGATE_ID]?.s() ?: "",
            payload = this[OutboxEvent.PAYLOAD]?.s() ?: "",
            status = OutboxStatus.valueOf(this[OutboxEvent.STATUS]?.s() ?: "PENDING"),
            attempts = this[OutboxEvent.ATTEMPTS]?.n()?.toInt() ?: 0,
            nextRetryAt = this[OutboxEvent.NEXT_RETRY_AT]?.s()?.let { Instant.parse(it) },
            lastError = this[OutboxEvent.LAST_ERROR]?.s(),
            traceId = this[OutboxEvent.TRACE_ID]?.s(),
            createdAt = Instant.parse(this[OutboxEvent.CREATED_AT]?.s() ?: Instant.now().toString()),
            updatedAt = Instant.parse(this[OutboxEvent.UPDATED_AT]?.s() ?: Instant.now().toString())
        )
    }
}
