package com.traffictacos.reservation.service

import com.traffictacos.reservation.domain.Reservation
import com.traffictacos.reservation.domain.ReservationStatus
import com.traffictacos.reservation.dto.CreateReservationRequest
import com.traffictacos.reservation.dto.ErrorCode
import com.traffictacos.reservation.grpc.InventoryGrpcClient
import com.traffictacos.reservation.repository.OrderRepository
import com.traffictacos.reservation.repository.ReservationRepository
import com.traffictacos.inventory.v1.CheckAvailabilityResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import java.time.Instant
import java.util.*

class ReservationServiceTest {

    private lateinit var reservationRepository: ReservationRepository
    private lateinit var orderRepository: OrderRepository
    private lateinit var inventoryGrpcClient: InventoryGrpcClient
    private lateinit var reservationExpiryService: ReservationExpiryService
    private lateinit var outboxEventPublisher: OutboxEventPublisher
    private lateinit var reservationService: ReservationService

    @BeforeEach
    fun setUp() {
        reservationRepository = mock()
        orderRepository = mock()
        inventoryGrpcClient = mock()
        reservationExpiryService = mock()
        outboxEventPublisher = mock()

        reservationService = ReservationService(
            reservationRepository,
            orderRepository,
            inventoryGrpcClient,
            reservationExpiryService,
            outboxEventPublisher
        )
    }

    @Test
    fun `createReservation should create reservation successfully when seats are available`() = runBlocking {
        // Given
        val request = CreateReservationRequest(
            eventId = "event-1",
            quantity = 2,
            seatIds = emptyList(),
            reservationToken = "token-123"
        )
        val userId = "user-123"
        val idempotencyKey = "idem-key-123"

        val availabilityResponse = CheckAvailabilityResponse.newBuilder()
            .setAvailable(true)
            .addAllAvailableSeatIds(listOf("A-1", "A-2"))
            .build()

        val savedReservation = Reservation(
            reservationId = "reservation-123",
            eventId = request.eventId,
            userId = userId,
            quantity = request.quantity,
            seatIds = listOf("A-1", "A-2"),
            status = ReservationStatus.HOLD,
            holdExpiresAt = Instant.now().plusSeconds(60),
            idempotencyKey = idempotencyKey
        )

        whenever(inventoryGrpcClient.checkAvailability(request.eventId, request.quantity, request.seatIds))
            .thenReturn(availabilityResponse)
        whenever(reservationRepository.saveAsync(any())).thenReturn(savedReservation)

        // When
        val response = reservationService.createReservation(request, userId, idempotencyKey)

        // Then
        assertNotNull(response.reservationId)
        assertEquals(ReservationStatus.HOLD, response.status)
        assertNotNull(response.holdExpiresAt)
        assertEquals("Reservation created successfully", response.message)

        verify(inventoryGrpcClient).checkAvailability(request.eventId, request.quantity, request.seatIds)
        verify(reservationRepository).saveAsync(any())
        verify(reservationExpiryService).scheduleExpiry(any(), any())
        verify(outboxEventPublisher).publishReservationCreated(any())
    }

    @Test
    fun `createReservation should throw exception when seats are not available`() = runBlocking {
        // Given
        val request = CreateReservationRequest(
            eventId = "event-1",
            quantity = 2,
            seatIds = emptyList(),
            reservationToken = "token-123"
        )
        val userId = "user-123"
        val idempotencyKey = "idem-key-123"

        val availabilityResponse = CheckAvailabilityResponse.newBuilder()
            .setAvailable(false)
            .setMessage("Seats not available")
            .build()

        whenever(inventoryGrpcClient.checkAvailability(request.eventId, request.quantity, request.seatIds))
            .thenReturn(availabilityResponse)

        // When & Then
        val exception = assertThrows<ReservationException> {
            reservationService.createReservation(request, userId, idempotencyKey)
        }

        assertEquals(ErrorCode.SEAT_UNAVAILABLE, exception.errorCode)
        assertTrue(exception.message.contains("Seats not available"))

        verify(inventoryGrpcClient).checkAvailability(request.eventId, request.quantity, request.seatIds)
        verifyNoInteractions(reservationRepository)
        verifyNoInteractions(reservationExpiryService)
        verifyNoInteractions(outboxEventPublisher)
    }

    @Test
    fun `getReservation should return reservation details when reservation exists`() = runBlocking {
        // Given
        val reservationId = "reservation-123"
        val reservation = Reservation(
            reservationId = reservationId,
            eventId = "event-1",
            userId = "user-123",
            quantity = 2,
            seatIds = listOf("A-1", "A-2"),
            status = ReservationStatus.HOLD,
            holdExpiresAt = Instant.now().plusSeconds(60)
        )

        whenever(reservationRepository.findByIdAsync(reservationId)).thenReturn(reservation)

        // When
        val response = reservationService.getReservation(reservationId)

        // Then
        assertEquals(reservationId, response.reservationId)
        assertEquals("event-1", response.eventId)
        assertEquals(2, response.quantity)
        assertEquals(listOf("A-1", "A-2"), response.seatIds)
        assertEquals(ReservationStatus.HOLD, response.status)

        verify(reservationRepository).findByIdAsync(reservationId)
    }

    @Test
    fun `getReservation should throw exception when reservation does not exist`() = runBlocking {
        // Given
        val reservationId = "nonexistent-reservation"

        whenever(reservationRepository.findByIdAsync(reservationId)).thenReturn(null)

        // When & Then
        val exception = assertThrows<ReservationException> {
            reservationService.getReservation(reservationId)
        }

        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, exception.errorCode)

        verify(reservationRepository).findByIdAsync(reservationId)
    }
}