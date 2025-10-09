package com.traffictacos.reservation.domain

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*
import java.math.BigDecimal
import java.time.Instant

@DynamoDbBean
data class Order(
    @get:DynamoDbPartitionKey
    @get:DynamoDbAttribute("pk")
    var orderId: String = "",

    @get:DynamoDbSortKey
    @get:DynamoDbAttribute("sk")
    var reservationId: String = "",
    
    var eventId: String = "",
    var userId: String = "",
    var amount: BigDecimal = BigDecimal.ZERO,
    var status: OrderStatus = OrderStatus.PENDING,
    var paymentIntentId: String = "",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)

enum class OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    REFUNDED
}