package com.traffictacos.reservation.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.traffictacos.reservation.domain.ReservationStatus
import com.traffictacos.reservation.dto.*
import com.traffictacos.reservation.service.IdempotencyService
import com.traffictacos.reservation.service.ReservationException
import com.traffictacos.reservation.service.ReservationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

@WebFluxTest(ReservationController::class)
class ReservationControllerTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockBean
    private lateinit var reservationService: ReservationService

    @MockBean
    private lateinit var idempotencyService: IdempotencyService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @WithMockUser
    fun `createReservation should return 201 when reservation is created successfully`() {
        // Given
        val request = CreateReservationRequest(
            eventId = "event-1",
            quantity = 2,
            seatIds = emptyList(),
            reservationToken = "token-123"
        )

        val response = CreateReservationResponse(
            reservationId = "reservation-123",
            status = ReservationStatus.HOLD,
            holdExpiresAt = Instant.now().plusSeconds(60),
            message = "Reservation created successfully"
        )

        runBlocking {
            whenever(idempotencyService.executeIdempotent<CreateReservationRequest, CreateReservationResponse>(
                eq("idem-key-123"),
                eq(request),
                any()
            )).thenReturn(response)
        }

        // When & Then
        webTestClient
            .mutateWith(mockJwt().jwt { it.subject("user-123") })
            .post()
            .uri("/v1/reservations")
            .header("Idempotency-Key", "idem-key-123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.reservationId").isEqualTo("reservation-123")
            .jsonPath("$.status").isEqualTo("HOLD")
            .jsonPath("$.message").isEqualTo("Reservation created successfully")
    }

    @Test
    @WithMockUser
    fun `createReservation should return 400 when request is invalid`() {
        // Given
        val invalidRequest = CreateReservationRequest(
            eventId = "", // Invalid empty eventId
            quantity = 0, // Invalid quantity
            seatIds = emptyList(),
            reservationToken = ""
        )

        // When & Then
        webTestClient
            .mutateWith(mockJwt().jwt { it.subject("user-123") })
            .post()
            .uri("/v1/reservations")
            .header("Idempotency-Key", "idem-key-123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(invalidRequest)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    @WithMockUser
    fun `createReservation should return 409 when seats are not available`() {
        // Given
        val request = CreateReservationRequest(
            eventId = "event-1",
            quantity = 2,
            seatIds = emptyList(),
            reservationToken = "token-123"
        )

        val exception = ReservationException(
            ErrorCode.SEAT_UNAVAILABLE,
            "Requested seats are not available"
        )

        runBlocking {
            whenever(idempotencyService.executeIdempotent<CreateReservationRequest, CreateReservationResponse>(
                eq("idem-key-123"),
                eq(request),
                any()
            )).thenThrow(exception)
        }

        // When & Then
        webTestClient
            .mutateWith(mockJwt().jwt { it.subject("user-123") })
            .post()
            .uri("/v1/reservations")
            .header("Idempotency-Key", "idem-key-123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("SEAT_UNAVAILABLE")
            .jsonPath("$.error.message").isEqualTo("Requested seats are not available")
    }

    @Test
    @WithMockUser
    fun `getReservation should return 200 when reservation exists`() {
        // Given
        val reservationId = "reservation-123"
        val response = ReservationDetailsResponse(
            reservationId = reservationId,
            eventId = "event-1",
            quantity = 2,
            seatIds = listOf("A-1", "A-2"),
            status = ReservationStatus.HOLD,
            holdExpiresAt = Instant.now().plusSeconds(60),
            createdAt = Instant.now().minusSeconds(10),
            updatedAt = Instant.now().minusSeconds(10)
        )

        runBlocking {
            whenever(reservationService.getReservation(reservationId)).thenReturn(response)
        }

        // When & Then
        webTestClient
            .mutateWith(mockJwt().jwt { it.subject("user-123") })
            .get()
            .uri("/v1/reservations/{reservationId}", reservationId)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reservationId").isEqualTo(reservationId)
            .jsonPath("$.eventId").isEqualTo("event-1")
            .jsonPath("$.quantity").isEqualTo(2)
            .jsonPath("$.status").isEqualTo("HOLD")
    }

    @Test
    @WithMockUser
    fun `getReservation should return 404 when reservation does not exist`() {
        // Given
        val reservationId = "nonexistent-reservation"
        val exception = ReservationException(
            ErrorCode.RESERVATION_NOT_FOUND,
            "Reservation not found"
        )

        runBlocking {
            whenever(reservationService.getReservation(reservationId)).thenThrow(exception)
        }

        // When & Then
        webTestClient
            .mutateWith(mockJwt().jwt { it.subject("user-123") })
            .get()
            .uri("/v1/reservations/{reservationId}", reservationId)
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("RESERVATION_NOT_FOUND")
            .jsonPath("$.error.message").isEqualTo("Reservation not found")
    }

    @Test
    fun `createReservation should return 401 when not authenticated`() {
        // Given
        val request = CreateReservationRequest(
            eventId = "event-1",
            quantity = 2,
            seatIds = emptyList(),
            reservationToken = "token-123"
        )

        // When & Then
        webTestClient
            .post()
            .uri("/v1/reservations")
            .header("Idempotency-Key", "idem-key-123")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isUnauthorized
    }
}