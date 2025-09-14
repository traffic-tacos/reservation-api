package com.traffictacos.reservation.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/reservations")
class SimpleReservationController {

    @PostMapping
    fun createReservation(
        @RequestBody request: Map<String, Any>
    ): Mono<ResponseEntity<Map<String, String>>> {
        return Mono.just(
            ResponseEntity.ok(mapOf(
                "reservationId" to "rsv_${System.currentTimeMillis()}",
                "status" to "created",
                "message" to "Reservation created successfully"
            ))
        )
    }

    @GetMapping("/{id}")
    fun getReservation(@PathVariable id: String): Mono<ResponseEntity<Map<String, Any>>> {
        return Mono.just(
            ResponseEntity.ok(mapOf(
                "reservationId" to id,
                "status" to "HOLD",
                "eventId" to "evt_test",
                "message" to "Reservation found"
            ))
        )
    }

    @PostMapping("/{id}/confirm")
    fun confirmReservation(@PathVariable id: String): Mono<ResponseEntity<Map<String, String>>> {
        return Mono.just(
            ResponseEntity.ok(mapOf(
                "orderId" to "ord_${System.currentTimeMillis()}",
                "status" to "CONFIRMED"
            ))
        )
    }

    @PostMapping("/{id}/cancel")
    fun cancelReservation(@PathVariable id: String): Mono<ResponseEntity<Map<String, String>>> {
        return Mono.just(
            ResponseEntity.ok(mapOf(
                "status" to "CANCELLED"
            ))
        )
    }
}
