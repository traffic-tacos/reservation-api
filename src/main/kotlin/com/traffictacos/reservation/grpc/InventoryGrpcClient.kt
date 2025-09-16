package com.traffictacos.reservation.grpc

import com.traffictacos.inventory.v1.CheckReq
import com.traffictacos.inventory.v1.CheckRes
import com.traffictacos.inventory.v1.CommitReq
import com.traffictacos.inventory.v1.CommitRes
import com.traffictacos.inventory.v1.ReleaseReq
import com.traffictacos.inventory.v1.ReleaseRes
import com.traffictacos.inventory.v1.InventoryGrpcKt
import io.grpc.ManagedChannel
import io.grpc.StatusException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.time.Duration

private val logger = KotlinLogging.logger {}

data class AvailabilityResponse(
    val available: Boolean,
    val unavailableSeats: List<String> = emptyList()
)

data class CommitResponse(
    val orderId: String,
    val status: String
)

data class ReleaseResponse(
    val status: String
)

@Component
class InventoryGrpcClient(
    private val inventoryChannel: ManagedChannel,
    private val meterRegistry: MeterRegistry,
    @Value("\${grpc.client.inventory.timeout:250ms}") private val timeoutDuration: Duration
) {
    
    private val stub = InventoryGrpcKt.InventoryCoroutineStub(inventoryChannel)
    
    companion object {
        private const val GRPC_CALL_COUNTER = "grpc_calls_total"
        private const val GRPC_CALL_DURATION = "grpc_call_duration"
        private const val GRPC_ERROR_COUNTER = "grpc_errors_total"
    }

    fun checkAvailability(eventId: String, seatIds: List<String>, quantity: Int): Mono<AvailabilityResponse> {
        val timer = Timer.start(meterRegistry)
        
        return Mono.fromCallable {
            val request = CheckReq.newBuilder()
                .setEventId(eventId)
                .addAllSeatIds(seatIds)
                .setQty(quantity)
                .build()
            
            logger.debug { "Checking availability for event=$eventId, seats=$seatIds, qty=$quantity" }
            request
        }.flatMap { request ->
            executeWithTimeout("checkAvailability") {
                stub.checkAvailability(request)
            }.toMono()
        }.map { response ->
            AvailabilityResponse(
                available = response.available,
                unavailableSeats = response.unavailableSeatsList
            )
        }.doOnSuccess { response ->
            meterRegistry.counter(GRPC_CALL_COUNTER, "method", "checkAvailability", "status", "success").increment()
            timer.stop(Timer.Sample.builder(meterRegistry).register(GRPC_CALL_DURATION, "method", "checkAvailability"))
            logger.debug { "Availability check completed: available=${response.available}" }
        }.doOnError { error ->
            meterRegistry.counter(GRPC_ERROR_COUNTER, "method", "checkAvailability", "error", error.javaClass.simpleName).increment()
            timer.stop(Timer.Sample.builder(meterRegistry).register(GRPC_CALL_DURATION, "method", "checkAvailability"))
            logger.error(error) { "Failed to check availability for event=$eventId" }
        }
    }

    fun commitReservation(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        quantity: Int,
        paymentIntentId: String
    ): Mono<CommitResponse> {
        val timer = Timer.start(meterRegistry)
        
        return Mono.fromCallable {
            val request = CommitReq.newBuilder()
                .setReservationId(reservationId)
                .setEventId(eventId)
                .addAllSeatIds(seatIds)
                .setQty(quantity)
                .setPaymentIntentId(paymentIntentId)
                .build()
            
            logger.debug { "Committing reservation=$reservationId for event=$eventId, seats=$seatIds" }
            request
        }.flatMap { request ->
            executeWithTimeout("commitReservation") {
                stub.commitReservation(request)
            }.toMono()
        }.map { response ->
            CommitResponse(
                orderId = response.orderId,
                status = response.status
            )
        }.doOnSuccess { response ->
            meterRegistry.counter(GRPC_CALL_COUNTER, "method", "commitReservation", "status", "success").increment()
            timer.stop(Timer.Sample.builder(meterRegistry).register(GRPC_CALL_DURATION, "method", "commitReservation"))
            logger.info { "Reservation committed successfully: reservation=$reservationId, order=${response.orderId}" }
        }.doOnError { error ->
            meterRegistry.counter(GRPC_ERROR_COUNTER, "method", "commitReservation", "error", error.javaClass.simpleName).increment()
            timer.stop(Timer.Sample.builder(meterRegistry).register(GRPC_CALL_DURATION, "method", "commitReservation"))
            logger.error(error) { "Failed to commit reservation=$reservationId" }
        }
    }

    fun releaseHold(
        reservationId: String,
        eventId: String,
        seatIds: List<String>,
        quantity: Int
    ): Mono<ReleaseResponse> {
        val timer = Timer.start(meterRegistry)
        
        return Mono.fromCallable {
            val request = ReleaseReq.newBuilder()
                .setReservationId(reservationId)
                .setEventId(eventId)
                .addAllSeatIds(seatIds)
                .setQty(quantity)
                .build()
            
            logger.debug { "Releasing hold for reservation=$reservationId, event=$eventId, seats=$seatIds" }
            request
        }.flatMap { request ->
            executeWithTimeout("releaseHold") {
                stub.releaseHold(request)
            }.toMono()
        }.map { response ->
            ReleaseResponse(status = response.status)
        }.doOnSuccess { response ->
            meterRegistry.counter(GRPC_CALL_COUNTER, "method", "releaseHold", "status", "success").increment()
            timer.stop(Timer.Sample.builder(meterRegistry).register(GRPC_CALL_DURATION, "method", "releaseHold"))
            logger.debug { "Hold released successfully: reservation=$reservationId, status=${response.status}" }
        }.doOnError { error ->
            meterRegistry.counter(GRPC_ERROR_COUNTER, "method", "releaseHold", "error", error.javaClass.simpleName).increment()
            timer.stop(Timer.Sample.builder(meterRegistry).register(GRPC_CALL_DURATION, "method", "releaseHold"))
            logger.error(error) { "Failed to release hold for reservation=$reservationId" }
        }
    }

    private suspend fun <T> executeWithTimeout(methodName: String, operation: suspend () -> T): T {
        return try {
            withTimeout(timeoutDuration.toMillis()) {
                operation()
            }
        } catch (e: Exception) {
            when (e) {
                is StatusException -> {
                    logger.warn { "gRPC error in $methodName: ${e.status}" }
                    throw GrpcException("gRPC call failed: ${e.status.description}", e)
                }
                is kotlinx.coroutines.TimeoutCancellationException -> {
                    logger.warn { "gRPC timeout in $methodName after ${timeoutDuration.toMillis()}ms" }
                    throw GrpcTimeoutException("gRPC call timed out after ${timeoutDuration.toMillis()}ms", e)
                }
                else -> {
                    logger.error(e) { "Unexpected error in gRPC $methodName" }
                    throw GrpcException("Unexpected error in gRPC call", e)
                }
            }
        }
    }
}

// Custom exceptions for gRPC errors
class GrpcException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class GrpcTimeoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)