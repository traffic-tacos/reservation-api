package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.IdempotencyRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.time.Instant

@Repository
class IdempotencyRepository(
    private val dynamoDbClient: DynamoDbClient,
    @Value("\${app.dynamodb.tables.idempotency}") private val tableName: String
) {

    fun save(record: IdempotencyRecord): Mono<IdempotencyRecord> {
        return Mono.fromCallable {
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
                .conditionExpression("attribute_not_exists(${IdempotencyRecord.PK_NAME})")  // Idempotency check
                .build()

            try {
                dynamoDbClient.putItem(request)
                record
            } catch (e: ConditionalCheckFailedException) {
                // Item already exists, this is expected for idempotency
                findByKey(record.idempotencyKey).block() ?: record
            }
        }
    }

    fun findByKey(idempotencyKey: String): Mono<IdempotencyRecord?> {
        return Mono.fromCallable {
            val key = mapOf(
                IdempotencyRecord.PK_NAME to AttributeValue.builder().s(idempotencyKey).build()
            )

            val request = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build()

            val response = dynamoDbClient.getItem(request)
            if (response.hasItem()) {
                val item = response.item()
                val ttl = item[IdempotencyRecord.TTL]?.n()?.toLong()?.let { Instant.ofEpochSecond(it) }

                if (ttl != null && Instant.now().isAfter(ttl)) {
                    // TTL expired, delete the record
                    deleteByKey(idempotencyKey).block()
                    null
                } else {
                    IdempotencyRecord(
                        idempotencyKey = item[IdempotencyRecord.PK_NAME]?.s() ?: "",
                        requestHash = item[IdempotencyRecord.REQUEST_HASH]?.s() ?: "",
                        responseSnapshot = item[IdempotencyRecord.RESPONSE_SNAPSHOT]?.s() ?: "",
                        ttl = ttl ?: Instant.now().plusSeconds(300)  // Default 5 minutes
                    )
                }
            } else {
                null
            }
        }
    }

    fun deleteByKey(idempotencyKey: String): Mono<Unit> {
        return Mono.fromCallable {
            val key = mapOf(
                IdempotencyRecord.PK_NAME to AttributeValue.builder().s(idempotencyKey).build()
            )

            val request = DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build()

            dynamoDbClient.deleteItem(request)
        }
    }

    fun existsByKey(idempotencyKey: String): Mono<Boolean> {
        return findByKey(idempotencyKey).map { it != null }
    }

    fun cleanupExpiredRecords(): Mono<Int> {
        return Mono.fromCallable {
            val now = Instant.now().epochSecond

            val request = ScanRequest.builder()
                .tableName(tableName)
                .filterExpression("#ttl < :now")
                .expressionAttributeNames(mapOf("#ttl" to IdempotencyRecord.TTL))
                .expressionAttributeValues(
                    mapOf(":now" to AttributeValue.builder().n(now.toString()).build())
                )
                .build()

            val response = dynamoDbClient.scan(request)
            var deletedCount = 0

            for (item in response.items()) {
                val key = item[IdempotencyRecord.PK_NAME]?.s()
                if (key != null) {
                    deleteByKey(key).block()
                    deletedCount++
                }
            }

            deletedCount
        }
    }
}
