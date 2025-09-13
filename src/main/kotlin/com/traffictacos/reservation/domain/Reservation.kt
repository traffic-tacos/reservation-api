package com.traffictacos.reservation.domain

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.util.*

data class Reservation(
    val reservationId: String = UUID.randomUUID().toString(),
    val eventId: String,
    val userId: String,
    val qty: Int,
    val seatIds: List<String>,
    val status: ReservationStatus = ReservationStatus.HOLD,
    val holdExpiresAt: Instant? = null,
    val idempotencyKey: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {

    fun isExpired(): Boolean {
        return status == ReservationStatus.HOLD &&
               holdExpiresAt != null &&
               Instant.now().isAfter(holdExpiresAt)
    }

    fun canBeConfirmed(): Boolean {
        return status == ReservationStatus.HOLD && !isExpired()
    }

    fun canBeCancelled(): Boolean {
        return status == ReservationStatus.HOLD && !isExpired()
    }

    fun confirm(): Reservation {
        require(canBeConfirmed()) { "Reservation cannot be confirmed" }
        return copy(
            status = ReservationStatus.CONFIRMED,
            updatedAt = Instant.now()
        )
    }

    fun cancel(): Reservation {
        require(canBeCancelled()) { "Reservation cannot be cancelled" }
        return copy(
            status = ReservationStatus.CANCELLED,
            updatedAt = Instant.now()
        )
    }

    fun expire(): Reservation {
        return copy(
            status = ReservationStatus.EXPIRED,
            updatedAt = Instant.now()
        )
    }

    companion object {
        // DynamoDB Key Schema
        const val TABLE_NAME = "reservations"
        const val PK_NAME = "pk"  // reservation_id
        const val SK_NAME = "sk"  // event_id

        // Attributes
        const val EVENT_ID = "event_id"
        const val USER_ID = "user_id"
        const val QTY = "qty"
        const val SEAT_IDS = "seat_ids"
        const val STATUS = "status"
        const val HOLD_EXPIRES_AT = "hold_expires_at"
        const val IDEMPOTENCY_KEY = "idempotency_key"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }
}

enum class ReservationStatus {
    HOLD,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
