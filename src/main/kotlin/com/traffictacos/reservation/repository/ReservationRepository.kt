package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Reservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
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

    /**
     * 비동기적으로 예약을 저장합니다.
     * 
     * DynamoDB Enhanced Client는 블로킹 API이므로,
     * Dispatchers.IO 컨텍스트에서 실행하여 Event Loop 스레드를 블로킹하지 않습니다.
     */
    suspend fun saveAsync(reservation: Reservation): Reservation = withContext(Dispatchers.IO) {
        reservationTable.putItem(reservation)
        reservation
    }

    /**
     * 비동기적으로 예약을 조회합니다.
     * 
     * DynamoDB Enhanced Client는 블로킹 API이므로,
     * Dispatchers.IO 컨텍스트에서 실행하여 Event Loop 스레드를 블로킹하지 않습니다.
     */
    suspend fun findByIdAsync(reservationId: String): Reservation? = withContext(Dispatchers.IO) {
        val key = Key.builder()
            .partitionValue(reservationId)
            .build()

        reservationTable.getItem(key)
    }
}