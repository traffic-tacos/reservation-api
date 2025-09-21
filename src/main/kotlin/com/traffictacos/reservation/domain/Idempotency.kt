package com.traffictacos.reservation.domain

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*
import java.time.Instant

@DynamoDbBean
data class Idempotency(
    @get:DynamoDbPartitionKey
    var idempotencyKey: String = "",

    var requestHash: String = "",
    var responseSnapshot: String = "",
    var ttl: Long = 0, // TTL for automatic cleanup (5 minutes)
    var createdAt: Instant = Instant.now()
)