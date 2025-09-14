package com.traffictacos.reservation.service

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Profile("!local")
@Service
class OutboxEventPublisher {
    // Outbox event publisher for non-local profiles
    // This will be implemented when needed
}