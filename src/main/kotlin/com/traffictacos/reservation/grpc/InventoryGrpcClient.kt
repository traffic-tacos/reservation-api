package com.traffictacos.reservation.grpc

import com.traffictacos.reservation.grpc.inventory.CheckAvailabilityRequest
import com.traffictacos.reservation.grpc.inventory.CheckAvailabilityResponse
import com.traffictacos.reservation.grpc.inventory.CommitReservationRequest
import com.traffictacos.reservation.grpc.inventory.CommitReservationResponse
import com.traffictacos.reservation.grpc.inventory.InventoryGrpc
import com.traffictacos.reservation.grpc.inventory.ReleaseHoldRequest
import com.traffictacos.reservation.grpc.inventory.ReleaseHoldResponse
import io.grpc.ManagedChannel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

@Service
class InventoryGrpcClient(
    private val inventoryChannel: ManagedChannel
) {
    private val logger = LoggerFactory.getLogger(InventoryGrpcClient::class.java)
    private val stub = InventoryGrpc.newFutureStub(inventoryChannel)

    fun checkAvailability(eventId: String, seatIds: List<String>, qty: Int): Mono<CheckAvailabilityResponse> {
        return Mono.fromCallable {
            logger.debug("Checking availability for event: {}, seats: {}, qty: {}", eventId, seatIds, qty)

            val request = CheckAvailabilityRequest.newBuilder()
                .setEventId(eventId)
                .addAllSeatIds(seatIds)
                .setQty(qty)
                .build()

            val future = stub.withDeadlineAfter(250, TimeUnit.MILLISECONDS)
                .checkAvailability(request)

            val response = future.get(250, TimeUnit.MILLISECONDS)
            logger.debug("Availability check result: available={}, unavailable={}",
                response.available, response.unavailableSeatsList)

            response
        }.doOnError { error ->
            logger.error("Failed to check availability for event: {}", eventId, error)
        }
    }

    fun commitReservation(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        qty: Int,
        paymentIntentId: String
    ): Mono<CommitReservationResponse> {
        return Mono.fromCallable {
            logger.debug("Committing reservation: {}, event: {}, seats: {}",
                reservationId, eventId, seatIds)

            val request = CommitReservationRequest.newBuilder()
                .setReservationId(reservationId)
                .setEventId(eventId)
                .addAllSeatIds(seatIds)
                .setQty(qty)
                .setPaymentIntentId(paymentIntentId)
                .build()

            val future = stub.withDeadlineAfter(250, TimeUnit.MILLISECONDS)
                .commitReservation(request)

            val response = future.get(250, TimeUnit.MILLISECONDS)
            logger.debug("Reservation commit result: success={}, orderId={}",
                response.success, response.orderId)

            response
        }.doOnError { error ->
            logger.error("Failed to commit reservation: {}", reservationId, error)
        }
    }

    fun releaseHold(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        qty: Int
    ): Mono<ReleaseHoldResponse> {
        return Mono.fromCallable {
            logger.debug("Releasing hold for reservation: {}, event: {}, seats: {}",
                reservationId, eventId, seatIds)

            val request = ReleaseHoldRequest.newBuilder()
                .setReservationId(reservationId)
                .setEventId(eventId)
                .addAllSeatIds(seatIds)
                .setQty(qty)
                .build()

            val future = stub.withDeadlineAfter(250, TimeUnit.MILLISECONDS)
                .releaseHold(request)

            val response = future.get(250, TimeUnit.MILLISECONDS)
            logger.debug("Hold release result: success={}", response.success)

            response
        }.doOnError { error ->
            logger.error("Failed to release hold for reservation: {}", reservationId, error)
        }
    }
}
