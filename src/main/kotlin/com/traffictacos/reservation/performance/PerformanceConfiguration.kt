package com.traffictacos.reservation.performance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.afterburner.AfterburnerModule
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import software.amazon.awssdk.core.client.config.ClientAsyncConfiguration
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
@EnableAsync
@EnableScheduling
class PerformanceConfiguration {

    @Bean
    @Primary
    fun optimizedObjectMapper(): ObjectMapper {
        return ObjectMapper().apply {
            // Register Afterburner module for faster JSON processing
            registerModule(AfterburnerModule())
            
            // Configure for performance
            configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            configure(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII, false)
        }
    }

    @Bean
    fun webServerCustomizer(): WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {
        return WebServerFactoryCustomizer { factory ->
            factory.addServerCustomizers { server ->
                server.option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
            }
        }
    }

    @Bean
    fun optimizedConnectionProvider(): ConnectionProvider {
        return ConnectionProvider.builder("reservation-api")
            .maxConnections(200) // Max connections per host
            .maxIdleTime(Duration.ofSeconds(30)) // Idle timeout
            .maxLifeTime(Duration.ofMinutes(10)) // Connection lifetime
            .pendingAcquireTimeout(Duration.ofSeconds(5)) // Wait time for connection
            .evictInBackground(Duration.ofSeconds(30)) // Background cleanup
            .build()
    }

    @Bean
    fun optimizedHttpClient(connectionProvider: ConnectionProvider): HttpClient {
        return HttpClient.create(connectionProvider)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(10))
                    .addHandlerLast(WriteTimeoutHandler(10))
            }
            .responseTimeout(Duration.ofSeconds(10))
    }

    @Bean
    fun optimizedWebClient(httpClient: HttpClient, objectMapper: ObjectMapper): WebClient {
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(1024 * 1024) // 1MB
                configurer.defaultCodecs().enableLoggingRequestDetails(false) // Disable for performance
            }
            .build()
    }

    @Bean("asyncExecutor")
    fun asyncExecutor(meterRegistry: MeterRegistry): Executor {
        val executor = ThreadPoolTaskExecutor().apply {
            corePoolSize = 10
            maxPoolSize = 50
            queueCapacity = 1000
            setThreadNamePrefix("AsyncTask-")
            
            // Performance optimizations
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            
            initialize()
        }

        // Monitor thread pool metrics
        meterRegistry.gauge("threadpool.async.core_size", executor.corePoolSize)
        meterRegistry.gauge("threadpool.async.max_size", executor.maxPoolSize)
        meterRegistry.gauge("threadpool.async.active_count") { executor.activeCount }
        meterRegistry.gauge("threadpool.async.pool_size") { executor.poolSize }
        meterRegistry.gauge("threadpool.async.queue_size") { executor.threadPoolExecutor?.queue?.size ?: 0 }

        return executor
    }

    @Bean("eventProcessingExecutor")
    fun eventProcessingExecutor(meterRegistry: MeterRegistry): Executor {
        val executor = ThreadPoolTaskExecutor().apply {
            corePoolSize = 5
            maxPoolSize = 20
            queueCapacity = 2000
            setThreadNamePrefix("EventProcessor-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            initialize()
        }

        // Monitor event processing thread pool
        meterRegistry.gauge("threadpool.event.active_count") { executor.activeCount }
        meterRegistry.gauge("threadpool.event.queue_size") { executor.threadPoolExecutor?.queue?.size ?: 0 }

        return executor
    }

    @Bean
    fun optimizedDynamoDbAsyncClient(): DynamoDbAsyncClient {
        val asyncConfig = ClientAsyncConfiguration.builder()
            .advancedOption(software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR) {
                java.util.concurrent.ForkJoinPool.commonPool()
            }
            .build()

        return DynamoDbAsyncClient.builder()
            .asyncConfiguration(asyncConfig)
            .httpClientBuilder(
                software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(100) // Max concurrent requests
                    .maxPendingConnectionAcquires(10000)
                    .connectionTimeout(Duration.ofSeconds(5))
                    .connectionAcquisitionTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(30))
                    .writeTimeout(Duration.ofSeconds(30))
                    .connectionTimeToLive(Duration.ofMinutes(10))
                    .connectionMaxIdleTime(Duration.ofSeconds(30))
            )
            .build()
    }

    @Bean
    fun optimizedEventBridgeAsyncClient(): EventBridgeAsyncClient {
        val asyncConfig = ClientAsyncConfiguration.builder()
            .advancedOption(software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR) {
                java.util.concurrent.ForkJoinPool.commonPool()
            }
            .build()

        return EventBridgeAsyncClient.builder()
            .asyncConfiguration(asyncConfig)
            .httpClientBuilder(
                software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(50) // Lower concurrency for events
                    .maxPendingConnectionAcquires(5000)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofMinutes(1))
                    .writeTimeout(Duration.ofMinutes(1))
            )
            .build()
    }
}

@Configuration
class PerformanceMonitoringService(
    private val meterRegistry: MeterRegistry
) {

    @Bean
    fun performanceMetrics(): PerformanceMetrics {
        return PerformanceMetrics(meterRegistry)
    }
}

class PerformanceMetrics(
    private val meterRegistry: MeterRegistry
) {
    
    fun recordProcessingTime(operation: String, durationMs: Long) {
        meterRegistry.timer("performance.operation.duration", "operation", operation)
            .record(Duration.ofMillis(durationMs))
    }
    
    fun recordThroughput(operation: String, count: Long) {
        meterRegistry.counter("performance.operation.throughput", "operation", operation)
            .increment(count.toDouble())
    }
    
    fun recordMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        
        meterRegistry.gauge("performance.memory.used", usedMemory.toDouble())
        meterRegistry.gauge("performance.memory.free", freeMemory.toDouble())
        meterRegistry.gauge("performance.memory.max", maxMemory.toDouble())
        meterRegistry.gauge("performance.memory.utilization", (usedMemory.toDouble() / maxMemory) * 100)
    }
    
    fun recordCpuUsage() {
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        if (bean is com.sun.management.OperatingSystemMXBean) {
            val cpuUsage = bean.processCpuLoad * 100
            meterRegistry.gauge("performance.cpu.usage", cpuUsage)
        }
    }
    
    fun recordGcMetrics() {
        val gcBeans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
        gcBeans.forEach { gcBean ->
            meterRegistry.gauge("performance.gc.collections", "collector", gcBean.name, gcBean.collectionCount.toDouble())
            meterRegistry.gauge("performance.gc.time", "collector", gcBean.name, gcBean.collectionTime.toDouble())
        }
    }
    
    fun recordDatabasePoolMetrics(poolName: String, activeConnections: Int, idleConnections: Int, totalConnections: Int) {
        meterRegistry.gauge("performance.db.pool.active", "pool", poolName, activeConnections.toDouble())
        meterRegistry.gauge("performance.db.pool.idle", "pool", poolName, idleConnections.toDouble())
        meterRegistry.gauge("performance.db.pool.total", "pool", poolName, totalConnections.toDouble())
        meterRegistry.gauge("performance.db.pool.utilization", "pool", poolName, 
            if (totalConnections > 0) (activeConnections.toDouble() / totalConnections) * 100 else 0.0)
    }
}