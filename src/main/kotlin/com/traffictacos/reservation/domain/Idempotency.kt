package com.traffictacos.reservation.domain

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant

data class IdempotencyRecord(
    val idempotencyKey: String,
    val requestHash: String,
    val responseSnapshot: String,
    val ttl: Instant
) {

    fun isExpired(): Boolean {
        return Instant.now().isAfter(ttl)
    }

    companion object {
        // DynamoDB Key Schema
        const val TABLE_NAME = "idempotency"
        const val PK_NAME = "pk"  // idempotency_key

        // Attributes
        const val REQUEST_HASH = "request_hash"
        const val RESPONSE_SNAPSHOT = "response_snapshot"
        const val TTL = "ttl"
    }
}
