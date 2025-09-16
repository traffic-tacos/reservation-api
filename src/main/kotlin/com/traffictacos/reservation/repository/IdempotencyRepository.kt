package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.IdempotencyRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Repository
class IdempotencyRepository(
    private val dynamoDbAsyncClient: DynamoDbAsyncClient,
    @Value("\${app.dynamodb.tables.idempotency}") private val tableName: String
) {

    fun save(record: IdempotencyRecord): Mono<IdempotencyRecord> {
        logger.debug { "Saving idempotency record: ${record.idempotencyKey}" }
        
        val item = mutableMapOf<String, AttributeValue>()

        // Primary Key
        item[IdempotencyRecord.PK_NAME] = AttributeValue.builder().s(record.idempotencyKey).build()

        // Attributes
        item[IdempotencyRecord.REQUEST_HASH] = AttributeValue.builder().s(record.requestHash).build()
        item[IdempotencyRecord.RESPONSE_SNAPSHOT] = AttributeValue.builder().s(record.responseSnapshot).build()
        item[IdempotencyRecord.TTL] = AttributeValue.builder().n(record.ttl.epochSecond.toString()).build()

        val request = PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
            .conditionExpression("attribute_not_exists(${IdempotencyRecord.PK_NAME})")
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.putItem(request) }
            .map { record }
            .onErrorResume { error ->
                when (error) {
                    is ConditionalCheckFailedException -> {
                        logger.debug { "Idempotency record already exists: ${record.idempotencyKey}" }
                        findByKey(record.idempotencyKey)
                            .map { it ?: record }
                    }
                    else -> {
                        logger.error(error) { "Failed to save idempotency record: ${record.idempotencyKey}" }
                        Mono.error(error)
                    }
                }
            }
            .doOnSuccess { logger.debug { "Successfully saved idempotency record: ${it.idempotencyKey}" } }
    }

    fun findByKey(idempotencyKey: String): Mono<IdempotencyRecord?> {
        logger.debug { "Finding idempotency record: $idempotencyKey" }
        
        val key = mapOf(
            IdempotencyRecord.PK_NAME to AttributeValue.builder().s(idempotencyKey).build()
        )

        val request = GetItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.getItem(request) }
            .flatMap { response ->
                if (response.hasItem()) {
                    val item = response.item()
                    val ttl = item[IdempotencyRecord.TTL]?.n()?.toLong()?.let { Instant.ofEpochSecond(it) }

                    if (ttl != null && Instant.now().isAfter(ttl)) {
                        // TTL expired, delete the record
                        logger.debug { "Idempotency record expired, deleting: $idempotencyKey" }
                        deleteByKey(idempotencyKey).then(Mono.just(null as IdempotencyRecord?))
                    } else {
                        val record = IdempotencyRecord(
                            idempotencyKey = item[IdempotencyRecord.PK_NAME]?.s() ?: "",
                            requestHash = item[IdempotencyRecord.REQUEST_HASH]?.s() ?: "",
                            responseSnapshot = item[IdempotencyRecord.RESPONSE_SNAPSHOT]?.s() ?: "",
                            ttl = ttl ?: Instant.now().plusSeconds(300)
                        )
                        Mono.just(record)
                    }
                } else {
                    Mono.just(null as IdempotencyRecord?)
                }
            }
            .doOnSuccess { record ->
                if (record != null) {
                    logger.debug { "Found idempotency record: ${record.idempotencyKey}" }
                } else {
                    logger.debug { "Idempotency record not found: $idempotencyKey" }
                }
            }
            .doOnError { error -> logger.error(error) { "Failed to find idempotency record: $idempotencyKey" } }
    }

    fun deleteByKey(idempotencyKey: String): Mono<Unit> {
        logger.debug { "Deleting idempotency record: $idempotencyKey" }
        
        val key = mapOf(
            IdempotencyRecord.PK_NAME to AttributeValue.builder().s(idempotencyKey).build()
        )

        val request = DeleteItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.deleteItem(request) }
            .map { Unit }
            .doOnSuccess { logger.debug { "Successfully deleted idempotency record: $idempotencyKey" } }
            .doOnError { error -> logger.error(error) { "Failed to delete idempotency record: $idempotencyKey" } }
    }

    fun existsByKey(idempotencyKey: String): Mono<Boolean> {
        return findByKey(idempotencyKey).map { it != null }
    }

    fun cleanupExpiredRecords(): Mono<Int> {
        logger.debug { "Cleaning up expired idempotency records" }
        
        val now = Instant.now().epochSecond

        val request = ScanRequest.builder()
            .tableName(tableName)
            .filterExpression("#ttl < :now")
            .expressionAttributeNames(mapOf("#ttl" to IdempotencyRecord.TTL))
            .expressionAttributeValues(
                mapOf(":now" to AttributeValue.builder().n(now.toString()).build())
            )
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.scan(request) }
            .flatMapMany { response ->
                reactor.core.publisher.Flux.fromIterable(response.items())
            }
            .flatMap { item ->
                val key = item[IdempotencyRecord.PK_NAME]?.s()
                if (key != null) {
                    deleteByKey(key).map { 1 }
                } else {
                    Mono.just(0)
                }
            }
            .reduce(0) { acc, count -> acc + count }
            .doOnSuccess { count -> logger.info { "Cleaned up $count expired idempotency records" } }
            .doOnError { error -> logger.error(error) { "Failed to cleanup expired idempotency records" } }
    }
}
