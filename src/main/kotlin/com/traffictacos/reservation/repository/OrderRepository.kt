package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Order
import kotlinx.coroutines.reactor.mono
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.Key

@Repository
class OrderRepository(
    private val orderTable: DynamoDbTable<Order>
) {

    fun save(order: Order): Mono<Order> = mono {
        orderTable.putItem(order)
        order
    }

    fun findById(orderId: String): Mono<Order?> = mono {
        val key = Key.builder()
            .partitionValue(orderId)
            .build()

        orderTable.getItem(key)
    }

    suspend fun saveAsync(order: Order): Order {
        orderTable.putItem(order)
        return order
    }

    suspend fun findByIdAsync(orderId: String): Order? {
        val key = Key.builder()
            .partitionValue(orderId)
            .build()

        return orderTable.getItem(key)
    }
}