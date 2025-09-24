package com.traffictacos.reservation.config

import com.traffic_tacos.reservation.v1.InventoryServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.annotation.PreDestroy

@Configuration
class GrpcConfig {

    @Value("\${grpc.client.inventory-service.address:localhost:8021}")
    private lateinit var inventoryServiceAddress: String

    private var managedChannel: ManagedChannel? = null

    @Bean
    fun inventoryServiceChannel(): ManagedChannel {
        val address = inventoryServiceAddress.removePrefix("static://")
        val parts = address.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8021

        managedChannel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
            .keepAliveTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .maxInboundMessageSize(4 * 1024 * 1024) // 4MB
            .build()

        return managedChannel!!
    }

    @Bean
    fun inventoryStub(channel: ManagedChannel): InventoryServiceGrpcKt.InventoryServiceCoroutineStub {
        return InventoryServiceGrpcKt.InventoryServiceCoroutineStub(channel)
    }

    @PreDestroy
    fun cleanup() {
        managedChannel?.shutdown()
    }
}