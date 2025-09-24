package com.traffictacos.reservation.grpc

import com.traffic_tacos.reservation.v1.ReservationServiceGrpcKt
import com.traffic_tacos.reservation.v1.CreateReservationRequest
import com.traffic_tacos.reservation.v1.CreateReservationResponse
import com.traffic_tacos.reservation.v1.GetReservationRequest
import com.traffic_tacos.reservation.v1.GetReservationResponse
import com.traffic_tacos.reservation.v1.ConfirmReservationRequest
import com.traffic_tacos.reservation.v1.ConfirmReservationResponse
import com.traffic_tacos.reservation.v1.CancelReservationRequest
import com.traffic_tacos.reservation.v1.CancelReservationResponse
import com.traffic_tacos.reservation.v1.ReservationStatus
import com.traffic_tacos.common.v1.Error
import com.traffic_tacos.common.v1.ErrorCode
import com.traffic_tacos.common.v1.Money
import com.traffictacos.reservation.service.ReservationService
import com.traffictacos.reservation.service.IdempotencyService
import com.traffictacos.reservation.service.ReservationException
import com.traffictacos.reservation.dto.CreateReservationRequest as DtoCreateReservationRequest
import com.traffictacos.reservation.dto.ConfirmReservationRequest as DtoConfirmReservationRequest
import com.traffictacos.reservation.dto.CancelReservationRequest as DtoCancelReservationRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import net.devh.boot.grpc.server.service.GrpcService
import com.google.protobuf.Timestamp
import java.time.Instant

@GrpcService
class ReservationGrpcService(
    private val reservationService: ReservationService,
    private val idempotencyService: IdempotencyService
) : ReservationServiceGrpcKt.ReservationServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(ReservationGrpcService::class.java)

    override suspend fun createReservation(request: CreateReservationRequest): CreateReservationResponse {
        logger.info("gRPC CreateReservation called for eventId: {}, userId: {}", request.eventId, request.userId)

        return try {
            val dtoRequest = DtoCreateReservationRequest(
                eventId = request.eventId,
                quantity = request.quantity,
                seatIds = request.seatIdsList,
                reservationToken = ""  // gRPC doesn't use this field
            )

            val response = if (request.idempotencyKey.isNotBlank()) {
                idempotencyService.executeIdempotent(
                    idempotencyKey = request.idempotencyKey,
                    request = dtoRequest
                ) {
                    reservationService.createReservation(dtoRequest, request.userId, request.idempotencyKey)
                }
            } else {
                reservationService.createReservation(dtoRequest, request.userId, "")
            }

            CreateReservationResponse.newBuilder()
                .setReservationId(response.reservationId)
                .setStatus(convertToGrpcStatus(response.status))
                .setHoldExpiresAt(response.holdExpiresAt?.let { convertToTimestamp(it) } ?: Timestamp.getDefaultInstance())
                .build()

        } catch (e: ReservationException) {
            logger.error("ReservationException in createReservation: {}", e.message)
            CreateReservationResponse.newBuilder()
                .setError(
                    Error.newBuilder()
                        .setCode(convertToGrpcErrorCode(e.errorCode))
                        .setMessage(e.message)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error("Unexpected error in createReservation", e)
            CreateReservationResponse.newBuilder()
                .setError(
                    Error.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    override suspend fun getReservation(request: GetReservationRequest): GetReservationResponse {
        logger.debug("gRPC GetReservation called for reservationId: {}", request.reservationId)

        return try {
            val response = reservationService.getReservation(request.reservationId)

            GetReservationResponse.newBuilder()
                .setReservation(
                    buildReservationMessage(response)
                )
                .build()

        } catch (e: ReservationException) {
            logger.error("ReservationException in getReservation: {}", e.message)
            GetReservationResponse.newBuilder()
                .setError(
                    Error.newBuilder()
                        .setCode(convertToGrpcErrorCode(e.errorCode))
                        .setMessage(e.message)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error("Unexpected error in getReservation", e)
            GetReservationResponse.newBuilder()
                .setError(
                    Error.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    override suspend fun confirmReservation(request: ConfirmReservationRequest): ConfirmReservationResponse {
        logger.info("gRPC ConfirmReservation called for reservationId: {}", request.reservationId)

        return try {
            val dtoRequest = DtoConfirmReservationRequest(
                reservationId = request.reservationId,
                paymentIntentId = request.paymentIntentId
            )

            val response = if (request.idempotencyKey.isNotBlank()) {
                idempotencyService.executeIdempotent(
                    idempotencyKey = request.idempotencyKey,
                    request = dtoRequest
                ) {
                    reservationService.confirmReservation(dtoRequest, request.userId)
                }
            } else {
                reservationService.confirmReservation(dtoRequest, request.userId)
            }

            ConfirmReservationResponse.newBuilder()
                .setOrderId(response.orderId ?: "")
                .setStatus(convertToGrpcStatus(response.status))
                .build()

        } catch (e: ReservationException) {
            logger.error("ReservationException in confirmReservation: {}", e.message)
            ConfirmReservationResponse.newBuilder()
                .setError(
                    Error.newBuilder()
                        .setCode(convertToGrpcErrorCode(e.errorCode))
                        .setMessage(e.message)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error("Unexpected error in confirmReservation", e)
            ConfirmReservationResponse.newBuilder()
                .setError(
                    Error.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    override suspend fun cancelReservation(request: CancelReservationRequest): CancelReservationResponse {
        logger.info("gRPC CancelReservation called for reservationId: {}", request.reservationId)

        return try {
            val dtoRequest = DtoCancelReservationRequest(
                reservationId = request.reservationId
            )

            val response = if (request.idempotencyKey.isNotBlank()) {
                idempotencyService.executeIdempotent(
                    idempotencyKey = request.idempotencyKey,
                    request = dtoRequest
                ) {
                    reservationService.cancelReservation(dtoRequest, request.userId)
                }
            } else {
                reservationService.cancelReservation(dtoRequest, request.userId)
            }

            CancelReservationResponse.newBuilder()
                .setStatus(convertToGrpcStatus(response.status))
                .build()

        } catch (e: ReservationException) {
            logger.error("ReservationException in cancelReservation: {}", e.message)
            CancelReservationResponse.newBuilder()
                .setError(
                    Error.newBuilder()
                        .setCode(convertToGrpcErrorCode(e.errorCode))
                        .setMessage(e.message)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            logger.error("Unexpected error in cancelReservation", e)
            CancelReservationResponse.newBuilder()
                .setError(
                    Error.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL)
                        .setMessage("Internal server error")
                        .build()
                )
                .build()
        }
    }

    private fun convertToGrpcStatus(status: com.traffictacos.reservation.domain.ReservationStatus): ReservationStatus {
        return when (status) {
            com.traffictacos.reservation.domain.ReservationStatus.PENDING -> ReservationStatus.RESERVATION_STATUS_HOLD
            com.traffictacos.reservation.domain.ReservationStatus.HOLD -> ReservationStatus.RESERVATION_STATUS_HOLD
            com.traffictacos.reservation.domain.ReservationStatus.CONFIRMED -> ReservationStatus.RESERVATION_STATUS_CONFIRMED
            com.traffictacos.reservation.domain.ReservationStatus.CANCELLED -> ReservationStatus.RESERVATION_STATUS_CANCELLED
            com.traffictacos.reservation.domain.ReservationStatus.EXPIRED -> ReservationStatus.RESERVATION_STATUS_EXPIRED
        }
    }

    private fun convertToGrpcErrorCode(errorCode: com.traffictacos.reservation.dto.ErrorCode): ErrorCode {
        return when (errorCode) {
            com.traffictacos.reservation.dto.ErrorCode.RESERVATION_NOT_FOUND -> ErrorCode.ERROR_CODE_NOT_FOUND
            com.traffictacos.reservation.dto.ErrorCode.SEAT_UNAVAILABLE -> ErrorCode.ERROR_CODE_INSUFFICIENT_INVENTORY
            com.traffictacos.reservation.dto.ErrorCode.RESERVATION_EXPIRED -> ErrorCode.ERROR_CODE_RESERVATION_EXPIRED
            com.traffictacos.reservation.dto.ErrorCode.RESERVATION_ALREADY_CONFIRMED -> ErrorCode.ERROR_CODE_INVALID_ARGUMENT
            com.traffictacos.reservation.dto.ErrorCode.RESERVATION_ALREADY_CANCELLED -> ErrorCode.ERROR_CODE_INVALID_ARGUMENT
            com.traffictacos.reservation.dto.ErrorCode.INVENTORY_SERVICE_ERROR -> ErrorCode.ERROR_CODE_INTERNAL
            else -> ErrorCode.ERROR_CODE_INTERNAL
        }
    }

    private fun convertToTimestamp(instant: Instant): Timestamp {
        return Timestamp.newBuilder()
            .setSeconds(instant.epochSecond)
            .setNanos(instant.nano)
            .build()
    }

    private fun buildReservationMessage(response: com.traffictacos.reservation.dto.ReservationDetailsResponse): com.traffic_tacos.reservation.v1.Reservation {
        return com.traffic_tacos.reservation.v1.Reservation.newBuilder()
            .setReservationId(response.reservationId)
            .setEventId(response.eventId)
            .setUserId(response.userId ?: "")
            .setStatus(convertToGrpcStatus(response.status))
            .setQuantity(response.quantity)
            .setCreatedAt(convertToTimestamp(response.createdAt))
            .setUpdatedAt(convertToTimestamp(response.updatedAt))
            .apply {
                response.holdExpiresAt?.let {
                    setHoldExpiresAt(convertToTimestamp(it))
                }
            }
            .build()
    }
}