package com.traffictacos.reservation.domain

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*
import java.time.Instant

@DynamoDbBean
data class OutboxEvent(
    @get:DynamoDbPartitionKey
    var outboxId: String = "",

    var eventType: String = "",
    var payload: String = "",
    var status: OutboxStatus = OutboxStatus.PENDING,
    var attempts: Int = 0,
    var nextRetryAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)

enum class OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}