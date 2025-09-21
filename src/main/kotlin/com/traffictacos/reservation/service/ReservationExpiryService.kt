package com.traffictacos.reservation.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.scheduler.SchedulerClient
import software.amazon.awssdk.services.scheduler.model.*
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service
class ReservationExpiryService {

    private val logger = LoggerFactory.getLogger(ReservationExpiryService::class.java)

    @Value("\${aws.region:ap-northeast-2}")
    private lateinit var region: String

    @Value("\${aws.profile:tacos}")
    private lateinit var profile: String

    @Value("\${aws.scheduler.endpoint:}")
    private lateinit var endpoint: String

    @Value("\${aws.eventbridge.scheduler-group:reservation-expiry}")
    private lateinit var schedulerGroup: String

    private val schedulerClient by lazy {
        val builder = SchedulerClient.builder()
            .region(Region.of(region))
            .credentialsProvider(ProfileCredentialsProvider.create(profile))

        // For local development with LocalStack
        if (endpoint.isNotEmpty()) {
            builder.endpointOverride(URI.create(endpoint))
        }

        builder.build()
    }

    suspend fun scheduleExpiry(reservationId: String, expiryTime: Instant) {
        try {
            logger.debug("Scheduling expiry for reservation: {} at {}", reservationId, expiryTime)

            val scheduleExpression = "at(${expiryTime.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))})"

            val target = Target.builder()
                .arn("arn:aws:events:${region}:123456789012:event-bus/traffic-tacos-events")
                .roleArn("arn:aws:iam::123456789012:role/ReservationSchedulerRole")
                .eventBridgeParameters(
                    EventBridgeParameters.builder()
                        .detailType("Reservation Expiry")
                        .source("reservation-api")
                        .detail("""
                            {
                                "reservationId": "$reservationId",
                                "eventType": "RESERVATION_EXPIRED",
                                "timestamp": "${Instant.now()}"
                            }
                        """.trimIndent())
                        .build()
                )
                .build()

            val createScheduleRequest = CreateScheduleRequest.builder()
                .name("reservation-expiry-$reservationId")
                .groupName(schedulerGroup)
                .scheduleExpression(scheduleExpression)
                .flexibleTimeWindow(
                    FlexibleTimeWindow.builder()
                        .mode(FlexibleTimeWindowMode.OFF)
                        .build()
                )
                .target(target)
                .description("Expiry schedule for reservation $reservationId")
                .build()

            schedulerClient.createSchedule(createScheduleRequest)

            logger.info("Scheduled expiry for reservation: {} at {}", reservationId, expiryTime)

        } catch (e: Exception) {
            logger.error("Failed to schedule expiry for reservation: {}", reservationId, e)
            // In a real implementation, you might want to handle this differently
            // For now, we'll log the error but not fail the reservation creation
        }
    }

    suspend fun cancelExpiry(reservationId: String) {
        try {
            logger.debug("Cancelling expiry schedule for reservation: {}", reservationId)

            val deleteScheduleRequest = DeleteScheduleRequest.builder()
                .name("reservation-expiry-$reservationId")
                .groupName(schedulerGroup)
                .build()

            schedulerClient.deleteSchedule(deleteScheduleRequest)

            logger.info("Cancelled expiry schedule for reservation: {}", reservationId)

        } catch (e: Exception) {
            logger.warn("Failed to cancel expiry schedule for reservation: {}", reservationId, e)
            // This is not critical, the schedule will just execute but the reservation might already be processed
        }
    }
}