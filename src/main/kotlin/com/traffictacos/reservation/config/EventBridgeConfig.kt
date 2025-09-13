package com.traffictacos.reservation.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import java.net.URI

@Configuration
class EventBridgeConfig {

    @Bean
    fun eventBridgeClient(
        @Value("\${aws.region}") region: String,
        @Value("\${aws.eventbridge.endpoint:}") endpoint: String?
    ): EventBridgeClient {
        val builder = EventBridgeClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())

        if (!endpoint.isNullOrBlank()) {
            builder.endpointOverride(URI.create(endpoint))
        }

        return builder.build()
    }
}
