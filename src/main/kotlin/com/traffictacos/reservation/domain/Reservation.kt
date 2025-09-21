package com.traffictacos.reservation.domain

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*
import java.time.Instant

@DynamoDbBean
data class Reservation(
    @get:DynamoDbPartitionKey
    var reservationId: String = "",

    var eventId: String = "",
    var userId: String = "",
    var quantity: Int = 0,
    var seatIds: List<String> = emptyList(),
    var status: ReservationStatus = ReservationStatus.PENDING,
    var holdExpiresAt: Instant? = null,
    var idempotencyKey: String = "",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)

enum class ReservationStatus {
    PENDING,
    HOLD,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}