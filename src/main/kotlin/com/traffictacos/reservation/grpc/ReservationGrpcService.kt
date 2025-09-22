package com.traffictacos.reservation.grpc

import reservationv1.ReservationServiceGrpcKt
import reservationv1.CreateReservationRequest
import reservationv1.CreateReservationResponse
import reservationv1.GetReservationRequest
import reservationv1.GetReservationResponse
import reservationv1.ConfirmReservationRequest
import reservationv1.ConfirmReservationResponse
import reservationv1.CancelReservationRequest
import reservationv1.CancelReservationResponse
import reservationv1.ReservationStatus
import commonv1.Error
import commonv1.ErrorCode
import commonv1.Money
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
                .setMessage(response.message)
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
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL_ERROR)
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
                .setReservationId(response.reservationId)
                .setEventId(response.eventId)
                .setQuantity(response.quantity)
                .addAllSeatIds(response.seatIds)
                .setStatus(convertToGrpcStatus(response.status))
                .setHoldExpiresAt(response.holdExpiresAt?.let { convertToTimestamp(it) } ?: Timestamp.getDefaultInstance())
                .setCreatedAt(convertToTimestamp(response.createdAt))
                .setUpdatedAt(convertToTimestamp(response.updatedAt))
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
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL_ERROR)
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
                .setOrderId(response.orderId)
                .setReservationId(response.reservationId)
                .setStatus(convertToGrpcStatus(response.status))
                .setMessage(response.message)
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
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL_ERROR)
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
                .setReservationId(response.reservationId)
                .setStatus(convertToGrpcStatus(response.status))
                .setMessage(response.message)
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
                        .setCode(ErrorCode.ERROR_CODE_INTERNAL_ERROR)
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
            com.traffictacos.reservation.dto.ErrorCode.RESERVATION_NOT_FOUND -> ErrorCode.ERROR_CODE_RESERVATION_NOT_FOUND
            com.traffictacos.reservation.dto.ErrorCode.SEAT_UNAVAILABLE -> ErrorCode.ERROR_CODE_SEATS_UNAVAILABLE
            com.traffictacos.reservation.dto.ErrorCode.RESERVATION_EXPIRED -> ErrorCode.ERROR_CODE_HOLD_EXPIRED
            com.traffictacos.reservation.dto.ErrorCode.RESERVATION_ALREADY_CONFIRMED -> ErrorCode.ERROR_CODE_INVALID_REQUEST
            com.traffictacos.reservation.dto.ErrorCode.RESERVATION_ALREADY_CANCELLED -> ErrorCode.ERROR_CODE_INVALID_REQUEST
            com.traffictacos.reservation.dto.ErrorCode.INVENTORY_SERVICE_ERROR -> ErrorCode.ERROR_CODE_INTERNAL_ERROR
            else -> ErrorCode.ERROR_CODE_INTERNAL_ERROR
        }
    }

    private fun convertToTimestamp(instant: Instant): Timestamp {
        return Timestamp.newBuilder()
            .setSeconds(instant.epochSecond)
            .setNanos(instant.nano)
            .build()
    }
}