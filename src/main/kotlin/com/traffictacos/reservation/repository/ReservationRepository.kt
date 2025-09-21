package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Reservation
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.Key

@Repository
class ReservationRepository(
    private val reservationTable: DynamoDbTable<Reservation>
) {

    fun save(reservation: Reservation): Mono<Reservation> = mono {
        reservationTable.putItem(reservation)
        reservation
    }

    fun findById(reservationId: String): Mono<Reservation?> = mono {
        val key = Key.builder()
            .partitionValue(reservationId)
            .build()

        reservationTable.getItem(key)
    }

    fun delete(reservationId: String): Mono<Void> = mono {
        val key = Key.builder()
            .partitionValue(reservationId)
            .build()

        reservationTable.deleteItem(key)
        null
    }

    suspend fun saveAsync(reservation: Reservation): Reservation {
        reservationTable.putItem(reservation)
        return reservation
    }

    suspend fun findByIdAsync(reservationId: String): Reservation? {
        val key = Key.builder()
            .partitionValue(reservationId)
            .build()

        return reservationTable.getItem(key)
    }
}