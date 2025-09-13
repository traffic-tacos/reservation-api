package com.traffictacos.reservation.config

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
class GrpcConfig {

    @Bean
    fun inventoryChannel(
        @Value("\${grpc.client.inventory.address}") address: String,
        @Value("\${grpc.client.inventory.negotiation-type:plaintext}") negotiationType: String
    ): ManagedChannel {
        val channelBuilder = ManagedChannelBuilder.forTarget(address)

        if (negotiationType == "plaintext") {
            channelBuilder.usePlaintext()
        }

        return channelBuilder
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()
    }
}
