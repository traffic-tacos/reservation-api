package com.traffictacos.reservation.config

import com.traffictacos.reservation.domain.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI

@Configuration
class DynamoDbConfig {

    @Value("\${aws.region:ap-northeast-2}")
    private lateinit var region: String

    @Value("\${aws.profile:}")
    private lateinit var profile: String

    @Value("\${aws.dynamodb.endpoint:}")
    private lateinit var endpoint: String

    @Value("\${aws.dynamodb.table.reservations:reservations}")
    private lateinit var reservationsTable: String

    @Value("\${aws.dynamodb.table.orders:orders}")
    private lateinit var ordersTable: String

    @Value("\${aws.dynamodb.table.idempotency:idempotency}")
    private lateinit var idempotencyTable: String

    @Value("\${aws.dynamodb.table.outbox:outbox}")
    private lateinit var outboxTable: String

    @Bean
    fun dynamoDbClient(): DynamoDbClient {
        val builder = DynamoDbClient.builder()
            .region(Region.of(region))
        
        // Use profile for local development, DefaultCredentialsProvider for K8s/IRSA
        if (profile.isNotEmpty()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(profile))
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create())
        }

        // For local development with LocalStack
        if (endpoint.isNotEmpty()) {
            builder.endpointOverride(URI.create(endpoint))
        }

        return builder.build()
    }

    @Bean
    fun dynamoDbEnhancedClient(dynamoDbClient: DynamoDbClient): DynamoDbEnhancedClient {
        return DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build()
    }

    @Bean
    fun reservationTable(enhancedClient: DynamoDbEnhancedClient): DynamoDbTable<Reservation> {
        return enhancedClient.table(reservationsTable, TableSchema.fromBean(Reservation::class.java))
    }

    @Bean
    fun orderTable(enhancedClient: DynamoDbEnhancedClient): DynamoDbTable<Order> {
        return enhancedClient.table(ordersTable, TableSchema.fromBean(Order::class.java))
    }

    @Bean
    fun idempotencyTable(enhancedClient: DynamoDbEnhancedClient): DynamoDbTable<Idempotency> {
        return enhancedClient.table(idempotencyTable, TableSchema.fromBean(Idempotency::class.java))
    }

    @Bean
    fun outboxTable(enhancedClient: DynamoDbEnhancedClient): DynamoDbTable<OutboxEvent> {
        return enhancedClient.table(outboxTable, TableSchema.fromBean(OutboxEvent::class.java))
    }
}