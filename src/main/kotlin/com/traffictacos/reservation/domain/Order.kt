package com.traffictacos.reservation.domain

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.util.*

data class Order(
    val orderId: String = UUID.randomUUID().toString(),
    val reservationId: String,
    val eventId: String,
    val userId: String,
    val seatIds: List<String>,
    val totalAmount: Long,
    val status: OrderStatus = OrderStatus.CONFIRMED,
    val paymentIntentId: String,
    val createdAt: Instant = Instant.now()
) {

    fun cancel(): Order {
        return copy(
            status = OrderStatus.CANCELLED
        )
    }

    fun refund(): Order {
        return copy(
            status = OrderStatus.REFUNDED
        )
    }

    companion object {
        // DynamoDB Key Schema
        const val TABLE_NAME = "orders"
        const val PK_NAME = "pk"  // order_id
        const val SK_NAME = "sk"  // reservation_id

        // Attributes
        const val RESERVATION_ID = "reservation_id"
        const val EVENT_ID = "event_id"
        const val USER_ID = "user_id"
        const val SEAT_IDS = "seat_ids"
        const val TOTAL_AMOUNT = "total_amount"
        const val STATUS = "status"
        const val PAYMENT_INTENT_ID = "payment_intent_id"
        const val CREATED_AT = "created_at"
    }
}

enum class OrderStatus {
    CONFIRMED,
    CANCELLED,
    REFUNDED
}
