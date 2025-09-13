package com.traffictacos.reservation.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI

@Configuration
class DynamoDbConfig {

    @Bean
    fun dynamoDbClient(
        @Value("\${aws.region}") region: String,
        @Value("\${aws.dynamodb.endpoint}") endpoint: String
    ): DynamoDbClient {
        return DynamoDbClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .endpointOverride(URI.create(endpoint))
            .build()
    }
}
