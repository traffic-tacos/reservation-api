package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
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

    /**
     * 비동기적으로 주문을 저장합니다.
     * 
     * Dispatchers.IO에서 실행하여 WebFlux Event Loop 스레드를 블로킹하지 않습니다.
     */
    suspend fun saveAsync(order: Order): Order = withContext(Dispatchers.IO) {
        orderTable.putItem(order)
        order
    }

    /**
     * 비동기적으로 주문을 조회합니다.
     * 
     * Dispatchers.IO에서 실행하여 WebFlux Event Loop 스레드를 블로킹하지 않습니다.
     */
    suspend fun findByIdAsync(orderId: String): Order? = withContext(Dispatchers.IO) {
        val key = Key.builder()
            .partitionValue(orderId)
            .build()

        orderTable.getItem(key)
    }
}