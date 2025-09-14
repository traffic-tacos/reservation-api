package com.traffictacos.reservation.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/health")
class HealthController {

    @GetMapping
    fun health(): Mono<ResponseEntity<Map<String, String>>> {
        return Mono.just(
            ResponseEntity.ok(mapOf(
                "status" to "UP",
                "service" to "reservation-api"
            ))
        )
    }
}
