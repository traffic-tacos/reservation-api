package com.traffictacos.reservation.repository

import com.traffictacos.reservation.domain.Reservation
import com.traffictacos.reservation.domain.ReservationStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.time.Instant
import java.util.*

@SpringBootTest
@Testcontainers
class ReservationRepositoryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
            .withServices(LocalStackContainer.Service.DYNAMODB)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("aws.dynamodb.endpoint") { localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB) }
            registry.add("aws.region") { localstack.region }
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { "https://test-issuer.com" }
        }
    }

    @Test
    fun `should save and retrieve reservation successfully`() = runBlocking {
        // Setup DynamoDB table
        setupDynamoDbTable()

        // Given
        val reservation = Reservation(
            reservationId = "test-reservation-${UUID.randomUUID()}",
            eventId = "event-1",
            userId = "user-123",
            quantity = 2,
            seatIds = listOf("A-1", "A-2"),
            status = ReservationStatus.HOLD,
            holdExpiresAt = Instant.now().plusSeconds(60),
            idempotencyKey = "idem-key-123"
        )

        val dynamoDbClient = createDynamoDbClient()
        val repository = createReservationRepository(dynamoDbClient)

        // When - Save reservation
        val savedReservation = repository.saveAsync(reservation)

        // Then - Verify saved reservation
        assertNotNull(savedReservation)
        assertEquals(reservation.reservationId, savedReservation.reservationId)
        assertEquals(reservation.eventId, savedReservation.eventId)
        assertEquals(reservation.userId, savedReservation.userId)
        assertEquals(reservation.quantity, savedReservation.quantity)
        assertEquals(reservation.seatIds, savedReservation.seatIds)
        assertEquals(reservation.status, savedReservation.status)

        // When - Retrieve reservation
        val retrievedReservation = repository.findByIdAsync(reservation.reservationId)

        // Then - Verify retrieved reservation
        assertNotNull(retrievedReservation)
        assertEquals(reservation.reservationId, retrievedReservation!!.reservationId)
        assertEquals(reservation.eventId, retrievedReservation.eventId)
        assertEquals(reservation.userId, retrievedReservation.userId)
        assertEquals(reservation.quantity, retrievedReservation.quantity)
        assertEquals(reservation.seatIds, retrievedReservation.seatIds)
        assertEquals(reservation.status, retrievedReservation.status)
    }

    @Test
    fun `findByIdAsync should return null when reservation does not exist`() = runBlocking {
        // Setup DynamoDB table
        setupDynamoDbTable()

        val dynamoDbClient = createDynamoDbClient()
        val repository = createReservationRepository(dynamoDbClient)

        // When
        val result = repository.findByIdAsync("nonexistent-reservation")

        // Then
        assertNull(result)
    }

    private fun setupDynamoDbTable() {
        val dynamoDbClient = createDynamoDbClient()

        try {
            // Create reservations table
            dynamoDbClient.createTable(
                CreateTableRequest.builder()
                    .tableName("reservations")
                    .attributeDefinitions(
                        AttributeDefinition.builder()
                            .attributeName("reservationId")
                            .attributeType(ScalarAttributeType.S)
                            .build()
                    )
                    .keySchema(
                        KeySchemaElement.builder()
                            .attributeName("reservationId")
                            .keyType(KeyType.HASH)
                            .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build()
            )

            // Wait for table to be ready
            dynamoDbClient.waiter().waitUntilTableExists { it.tableName("reservations") }
        } catch (e: ResourceInUseException) {
            // Table already exists, ignore
        }
    }

    private fun createDynamoDbClient(): DynamoDbClient {
        return DynamoDbClient.builder()
            .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")
                )
            )
            .region(Region.of(localstack.region))
            .build()
    }

    private fun createReservationRepository(dynamoDbClient: DynamoDbClient): ReservationRepository {
        // This is a simplified version for testing
        // In a real integration test, you would use the actual Spring context
        TODO("This requires actual Spring integration test setup")
    }
}