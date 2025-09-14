package com.traffictacos.reservation.service

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Profile("!local")
@Service
class ReservationExpiryService {
    // Reservation expiry service for non-local profiles
    // This will be implemented when needed
}