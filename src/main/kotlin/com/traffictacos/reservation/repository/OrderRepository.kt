package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Order
import com.traffictacos.reservation.domain.OrderStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.time.Instant

@Repository
class OrderRepository(
    private val dynamoDbClient: DynamoDbClient,
    @Value("\${app.dynamodb.tables.orders}") private val tableName: String
) {

    fun save(order: Order): Mono<Order> {
        return Mono.fromCallable {
            val item = mutableMapOf<String, AttributeValue>()

            // Primary Key
            item[Order.PK_NAME] = AttributeValue.builder().s(order.orderId).build()
            item[Order.SK_NAME] = AttributeValue.builder().s(order.reservationId).build()

            // Attributes
            item[Order.RESERVATION_ID] = AttributeValue.builder().s(order.reservationId).build()
            item[Order.EVENT_ID] = AttributeValue.builder().s(order.eventId).build()
            item[Order.USER_ID] = AttributeValue.builder().s(order.userId).build()
            item[Order.SEAT_IDS] = AttributeValue.builder().ss(order.seatIds).build()
            item[Order.TOTAL_AMOUNT] = AttributeValue.builder().n(order.totalAmount.toString()).build()
            item[Order.STATUS] = AttributeValue.builder().s(order.status.name).build()
            item[Order.PAYMENT_INTENT_ID] = AttributeValue.builder().s(order.paymentIntentId).build()
            item[Order.CREATED_AT] = AttributeValue.builder().s(order.createdAt.toString()).build()

            val request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build()

            dynamoDbClient.putItem(request)
            order
        }
    }

    fun findById(orderId: String): Mono<Order?> {
        return Mono.fromCallable {
            val key = mapOf(
                Order.PK_NAME to AttributeValue.builder().s(orderId).build()
            )

            val request = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build()

            val response = dynamoDbClient.getItem(request)
            if (response.hasItem()) {
                response.item().toOrder()
            } else {
                null
            }
        }
    }

    fun findByReservationId(reservationId: String): Mono<Order?> {
        return Mono.fromCallable {
            // Query by GSI if available, otherwise scan (not optimal for production)
            val request = QueryRequest.builder()
                .tableName(tableName)
                .indexName("reservation_id-index")  // Assuming GSI exists
                .keyConditionExpression("#reservationId = :reservationId")
                .expressionAttributeNames(mapOf("#reservationId" to Order.RESERVATION_ID))
                .expressionAttributeValues(
                    mapOf(":reservationId" to AttributeValue.builder().s(reservationId).build())
                )
                .build()

            val response = dynamoDbClient.query(request)
            response.items().firstOrNull()?.toOrder()
        }.onErrorResume {
            // Fallback to scan if GSI doesn't exist (for development)
            findByReservationIdScan(reservationId)
        }
    }

    private fun findByReservationIdScan(reservationId: String): Mono<Order?> {
        return Mono.fromCallable {
            val request = ScanRequest.builder()
                .tableName(tableName)
                .filterExpression("#reservationId = :reservationId")
                .expressionAttributeNames(mapOf("#reservationId" to Order.RESERVATION_ID))
                .expressionAttributeValues(
                    mapOf(":reservationId" to AttributeValue.builder().s(reservationId).build())
                )
                .build()

            val response = dynamoDbClient.scan(request)
            response.items().firstOrNull()?.toOrder()
        }
    }

    fun updateStatus(orderId: String, newStatus: OrderStatus): Mono<Order?> {
        return Mono.fromCallable {
            val key = mapOf(
                Order.PK_NAME to AttributeValue.builder().s(orderId).build()
            )

            val updateExpression = "SET #status = :status"
            val expressionAttributeNames = mapOf("#status" to Order.STATUS)
            val expressionAttributeValues = mapOf(
                ":status" to AttributeValue.builder().s(newStatus.name).build()
            )

            val request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpression)
                .expressionAttributeNames(expressionAttributeNames)
                .expressionAttributeValues(expressionAttributeValues)
                .returnValues(ReturnValue.ALL_NEW)
                .build()

            val response = dynamoDbClient.updateItem(request)
            response.attributes().toOrder()
        }
    }

    private fun Map<String, AttributeValue>.toOrder(): Order {
        return Order(
            orderId = this[Order.PK_NAME]?.s() ?: "",
            reservationId = this[Order.RESERVATION_ID]?.s() ?: "",
            eventId = this[Order.EVENT_ID]?.s() ?: "",
            userId = this[Order.USER_ID]?.s() ?: "",
            seatIds = this[Order.SEAT_IDS]?.ss() ?: emptyList(),
            totalAmount = this[Order.TOTAL_AMOUNT]?.n()?.toLong() ?: 0L,
            status = OrderStatus.valueOf(this[Order.STATUS]?.s() ?: "CONFIRMED"),
            paymentIntentId = this[Order.PAYMENT_INTENT_ID]?.s() ?: "",
            createdAt = Instant.parse(this[Order.CREATED_AT]?.s() ?: Instant.now().toString())
        )
    }
}
