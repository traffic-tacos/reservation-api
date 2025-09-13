package com.traffictacos.reservation.domain

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.util.*

data class OutboxEvent(
    val outboxId: String = UUID.randomUUID().toString(),
    val type: EventType,
    val aggregateType: String = "reservation",
    val aggregateId: String,
    val payload: String,  // JSON payload
    val status: OutboxStatus = OutboxStatus.PENDING,
    val attempts: Int = 0,
    val nextRetryAt: Instant? = null,
    val lastError: String? = null,
    val traceId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {

    fun markAsProcessing(): OutboxEvent {
        return copy(
            status = OutboxStatus.PROCESSING,
            attempts = attempts + 1,
            updatedAt = Instant.now()
        )
    }

    fun markAsCompleted(): OutboxEvent {
        return copy(
            status = OutboxStatus.COMPLETED,
            updatedAt = Instant.now()
        )
    }

    fun markAsFailed(error: String, nextRetryAt: Instant?): OutboxEvent {
        return copy(
            status = OutboxStatus.FAILED,
            lastError = error,
            nextRetryAt = nextRetryAt,
            updatedAt = Instant.now()
        )
    }

    fun canRetry(): Boolean {
        return status == OutboxStatus.FAILED &&
               attempts < MAX_RETRY_ATTEMPTS &&
               nextRetryAt?.let { Instant.now().isAfter(it) } ?: true
    }

    companion object {
        const val MAX_RETRY_ATTEMPTS = 5

        // DynamoDB Key Schema
        const val TABLE_NAME = "outbox"
        const val PK_NAME = "pk"  // outbox_id
        const val SK_NAME = "sk"  // aggregate_type#aggregate_id

        // GSI for processing
        const val GSI_STATUS_NAME = "status-created_at-index"
        const val GSI_STATUS_PK = "status"
        const val GSI_STATUS_SK = "created_at"

        // Attributes
        const val TYPE = "type"
        const val AGGREGATE_TYPE = "aggregate_type"
        const val AGGREGATE_ID = "aggregate_id"
        const val PAYLOAD = "payload"
        const val STATUS = "status"
        const val ATTEMPTS = "attempts"
        const val NEXT_RETRY_AT = "next_retry_at"
        const val LAST_ERROR = "last_error"
        const val TRACE_ID = "trace_id"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }
}

enum class EventType {
    RESERVATION_CREATED,
    RESERVATION_CONFIRMED,
    RESERVATION_CANCELLED,
    RESERVATION_EXPIRED
}

enum class OutboxStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
