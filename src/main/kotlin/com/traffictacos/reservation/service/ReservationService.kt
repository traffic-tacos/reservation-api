package com.traffictacos.reservation.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class ReservationService {

    fun createReservation(request: Map<String, Any>): Mono<Map<String, String>> {
        return Mono.just(
            mapOf(
                "reservationId" to "rsv_${System.currentTimeMillis()}",
                "status" to "created",
                "message" to "Reservation created successfully"
            )
        )
    }

    fun getReservation(id: String): Mono<Map<String, Any>> {
        return Mono.just(
            mapOf(
                "reservationId" to id,
                "status" to "HOLD",
                "eventId" to "evt_test",
                "message" to "Reservation found"
            )
        )
    }

    fun confirmReservation(id: String, request: Map<String, Any>): Mono<Map<String, String>> {
        return Mono.just(
            mapOf(
                "orderId" to "ord_${System.currentTimeMillis()}",
                "status" to "CONFIRMED"
            )
        )
    }

    fun cancelReservation(id: String): Mono<Map<String, String>> {
        return Mono.just(
            mapOf(
                "status" to "CANCELLED"
            )
        )
    }
}
