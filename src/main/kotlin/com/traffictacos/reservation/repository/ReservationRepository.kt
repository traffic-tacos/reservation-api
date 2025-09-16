package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Reservation
import com.traffictacos.reservation.domain.ReservationStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import reactor.core.publisher.Flux
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Repository
class ReservationRepository(
    private val dynamoDbAsyncClient: DynamoDbAsyncClient,
    @Value("\${app.dynamodb.tables.reservations}") private val tableName: String
) {

    fun save(reservation: Reservation): Mono<Reservation> {
        logger.debug { "Saving reservation: ${reservation.reservationId}" }
        
        val item = buildReservationItem(reservation)
        
        val request = PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.putItem(request) }
            .map { reservation }
            .doOnSuccess { logger.debug { "Successfully saved reservation: ${it.reservationId}" } }
            .doOnError { error -> logger.error(error) { "Failed to save reservation: ${reservation.reservationId}" } }
    }

    fun findById(reservationId: String): Mono<Reservation> {
        logger.debug { "Finding reservation by ID: $reservationId" }
        
        val key = mapOf(
            Reservation.PK_NAME to AttributeValue.builder().s(reservationId).build()
        )

        val request = GetItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.getItem(request) }
            .filter { it.hasItem() }
            .map { it.item().toReservation() }
            .doOnSuccess { logger.debug { "Found reservation: ${it?.reservationId}" } }
            .doOnError { error -> logger.error(error) { "Failed to find reservation: $reservationId" } }
    }

    fun findByIdAndEventId(reservationId: String, eventId: String): Mono<Reservation> {
        logger.debug { "Finding reservation by ID and event: $reservationId, $eventId" }
        
        val key = mapOf(
            Reservation.PK_NAME to AttributeValue.builder().s(reservationId).build(),
            Reservation.SK_NAME to AttributeValue.builder().s(eventId).build()
        )

        val request = GetItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.getItem(request) }
            .filter { it.hasItem() }
            .map { it.item().toReservation() }
            .doOnSuccess { logger.debug { "Found reservation: ${it?.reservationId}" } }
            .doOnError { error -> logger.error(error) { "Failed to find reservation: $reservationId" } }
    }

    fun findExpiredReservations(before: Instant): Flux<Reservation> {
        logger.debug { "Finding expired reservations before: $before" }
        
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

        return Mono.fromFuture { dynamoDbAsyncClient.scan(request) }
            .flatMapMany { response ->
                Flux.fromIterable(response.items())
                    .map { it.toReservation() }
            }
            .doOnComplete { logger.debug { "Completed finding expired reservations" } }
            .doOnError { error -> logger.error(error) { "Failed to find expired reservations" } }
    }

    fun updateStatus(reservationId: String, eventId: String, newStatus: ReservationStatus): Mono<Reservation> {
        logger.debug { "Updating reservation status: $reservationId to $newStatus" }
        
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

        return Mono.fromFuture { dynamoDbAsyncClient.updateItem(request) }
            .map { it.attributes().toReservation() }
            .doOnSuccess { logger.debug { "Successfully updated reservation status: ${it.reservationId}" } }
            .doOnError { error -> logger.error(error) { "Failed to update reservation status: $reservationId" } }
    }

    fun findByUserIdAndStatus(userId: String, status: ReservationStatus): Flux<Reservation> {
        logger.debug { "Finding reservations for user: $userId with status: $status" }
        
        // Note: This would be more efficient with a GSI on user_id + status
        val request = ScanRequest.builder()
            .tableName(tableName)
            .filterExpression("#userId = :userId AND #status = :status")
            .expressionAttributeNames(
                mapOf(
                    "#userId" to Reservation.USER_ID,
                    "#status" to Reservation.STATUS
                )
            )
            .expressionAttributeValues(
                mapOf(
                    ":userId" to AttributeValue.builder().s(userId).build(),
                    ":status" to AttributeValue.builder().s(status.name).build()
                )
            )
            .build()

        return Mono.fromFuture { dynamoDbAsyncClient.scan(request) }
            .flatMapMany { response ->
                Flux.fromIterable(response.items())
                    .map { it.toReservation() }
            }
            .doOnComplete { logger.debug { "Completed finding user reservations" } }
            .doOnError { error -> logger.error(error) { "Failed to find user reservations" } }
    }

    private fun buildReservationItem(reservation: Reservation): Map<String, AttributeValue> {
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

        return item
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