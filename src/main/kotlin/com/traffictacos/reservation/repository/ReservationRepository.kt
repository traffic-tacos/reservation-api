package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Reservation
import com.traffictacos.reservation.domain.ReservationStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.time.Instant

@Repository
class ReservationRepository(
    private val dynamoDbClient: DynamoDbClient,
    @Value("\${app.dynamodb.tables.reservations}") private val tableName: String
) {

    fun save(reservation: Reservation): Mono<Reservation> {
        return Mono.fromCallable {
            val item = mutableMapOf<String, AttributeValue>()

            // Primary Key
            item[Reservation.PK_NAME] = AttributeValue.builder().s(reservation.reservationId).build()
            item[Reservation.SK_NAME] = AttributeValue.builder().s(reservation.eventId).build()

            // Attributes
            item[Reservation.EVENT_ID] = AttributeValue.builder().s(reservation.eventId).build()
            item[Reservation.USER_ID] = AttributeValue.builder().s(reservation.userId).build()
            item[Reservation.QTY] = AttributeValue.builder().n(reservation.qty.toString()).build()
            item[Reservation.SEAT_IDS] = AttributeValue.builder().ss(reservation.seatIds).build()
            item[Reservation.STATUS] = AttributeValue.builder().s(reservation.status.name).build()

            reservation.holdExpiresAt?.let {
                item[Reservation.HOLD_EXPIRES_AT] = AttributeValue.builder().s(it.toString()).build()
            }

            reservation.idempotencyKey?.let {
                item[Reservation.IDEMPOTENCY_KEY] = AttributeValue.builder().s(it).build()
            }

            item[Reservation.CREATED_AT] = AttributeValue.builder().s(reservation.createdAt.toString()).build()
            item[Reservation.UPDATED_AT] = AttributeValue.builder().s(reservation.updatedAt.toString()).build()

            val request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build()

            dynamoDbClient.putItem(request)
            reservation
        }
    }

    fun findById(reservationId: String): Mono<Reservation?> {
        return Mono.fromCallable {
            val key = mapOf(
                Reservation.PK_NAME to AttributeValue.builder().s(reservationId).build()
            )

            val request = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build()

            val response = dynamoDbClient.getItem(request)
            if (response.hasItem()) {
                response.item().toReservation()
            } else {
                null
            }
        }
    }

    fun findByIdAndEventId(reservationId: String, eventId: String): Mono<Reservation?> {
        return Mono.fromCallable {
            val key = mapOf(
                Reservation.PK_NAME to AttributeValue.builder().s(reservationId).build(),
                Reservation.SK_NAME to AttributeValue.builder().s(eventId).build()
            )

            val request = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build()

            val response = dynamoDbClient.getItem(request)
            if (response.hasItem()) {
                response.item().toReservation()
            } else {
                null
            }
        }
    }

    fun findExpiredReservations(before: Instant): Mono<List<Reservation>> {
        return Mono.fromCallable {
            val request = ScanRequest.builder()
                .tableName(tableName)
                .filterExpression("#status = :status AND #expires < :before")
                .expressionAttributeNames(
                    mapOf(
                        "#status" to Reservation.STATUS,
                        "#expires" to Reservation.HOLD_EXPIRES_AT
                    )
                )
                .expressionAttributeValues(
                    mapOf(
                        ":status" to AttributeValue.builder().s(ReservationStatus.HOLD.name).build(),
                        ":before" to AttributeValue.builder().s(before.toString()).build()
                    )
                )
                .build()

            val response = dynamoDbClient.scan(request)
            response.items().map { it.toReservation() }
        }
    }

    fun updateStatus(reservationId: String, eventId: String, newStatus: ReservationStatus): Mono<Reservation?> {
        return Mono.fromCallable {
            val key = mapOf(
                Reservation.PK_NAME to AttributeValue.builder().s(reservationId).build(),
                Reservation.SK_NAME to AttributeValue.builder().s(eventId).build()
            )

            val updateExpression = "SET #status = :status, #updated = :updated"
            val expressionAttributeNames = mapOf(
                "#status" to Reservation.STATUS,
                "#updated" to Reservation.UPDATED_AT
            )
            val expressionAttributeValues = mapOf(
                ":status" to AttributeValue.builder().s(newStatus.name).build(),
                ":updated" to AttributeValue.builder().s(Instant.now().toString()).build()
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
            response.attributes().toReservation()
        }
    }

    private fun Map<String, AttributeValue>.toReservation(): Reservation {
        return Reservation(
            reservationId = this[Reservation.PK_NAME]?.s() ?: "",
            eventId = this[Reservation.EVENT_ID]?.s() ?: "",
            userId = this[Reservation.USER_ID]?.s() ?: "",
            qty = this[Reservation.QTY]?.n()?.toInt() ?: 0,
            seatIds = this[Reservation.SEAT_IDS]?.ss() ?: emptyList(),
            status = ReservationStatus.valueOf(this[Reservation.STATUS]?.s() ?: "HOLD"),
            holdExpiresAt = this[Reservation.HOLD_EXPIRES_AT]?.s()?.let { Instant.parse(it) },
            idempotencyKey = this[Reservation.IDEMPOTENCY_KEY]?.s(),
            createdAt = Instant.parse(this[Reservation.CREATED_AT]?.s() ?: Instant.now().toString()),
            updatedAt = Instant.parse(this[Reservation.UPDATED_AT]?.s() ?: Instant.now().toString())
        )
    }
}
