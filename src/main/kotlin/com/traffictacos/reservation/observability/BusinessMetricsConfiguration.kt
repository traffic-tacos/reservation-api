package com.traffictacos.reservation.observability

import com.traffictacos.reservation.domain.ReservationStatus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Configuration
class BusinessMetricsConfiguration {

    @Bean
    fun reservationMetrics(): ReservationMetrics {
        return ReservationMetrics()
    }

    class ReservationMetrics : MeterBinder {
        private val activeReservations = AtomicLong(0)
        private val totalRevenue = AtomicReference(0.0)
        private val averageProcessingTime = AtomicReference(Duration.ZERO)
        private val conversionRate = AtomicReference(0.0)
        
        // Business metrics counters
        private val reservationsByStatus = mutableMapOf<ReservationStatus, AtomicLong>()
        private val reservationsByEventType = mutableMapOf<String, AtomicLong>()
        private val processingTimes = mutableListOf<Duration>()

        init {
            ReservationStatus.values().forEach { status ->
                reservationsByStatus[status] = AtomicLong(0)
            }
        }

        override fun bindTo(registry: MeterRegistry) {
            // Active reservations gauge
            Gauge.builder("reservation.active.count")
                .description("Number of active reservations")
                .register(registry) { activeReservations.get().toDouble() }

            // Total revenue gauge
            Gauge.builder("reservation.revenue.total")
                .description("Total revenue from confirmed reservations")
                .baseUnit("USD")
                .register(registry) { totalRevenue.get() }

            // Average processing time gauge
            Gauge.builder("reservation.processing.time.average")
                .description("Average reservation processing time")
                .baseUnit("seconds")
                .register(registry) { averageProcessingTime.get().toMillis() / 1000.0 }

            // Conversion rate gauge (confirmed / created)
            Gauge.builder("reservation.conversion.rate")
                .description("Reservation conversion rate (confirmed/created)")
                .register(registry) { conversionRate.get() }

            // Reservation status gauges
            ReservationStatus.values().forEach { status ->
                Gauge.builder("reservation.status.count")
                    .tag("status", status.name)
                    .description("Number of reservations by status")
                    .register(registry) { reservationsByStatus[status]?.get()?.toDouble() ?: 0.0 }
            }

            // SLA compliance metrics
            Timer.builder("reservation.sla.processing")
                .description("Reservation processing time SLA compliance")
                .sla(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10))
                .register(registry)

            Timer.builder("reservation.sla.confirmation")
                .description("Reservation confirmation time SLA compliance")
                .sla(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30))
                .register(registry)
        }

        fun recordReservationCreated(eventType: String) {
            reservationsByStatus[ReservationStatus.HOLD]?.incrementAndGet()
            reservationsByEventType.computeIfAbsent(eventType) { AtomicLong(0) }.incrementAndGet()
            activeReservations.incrementAndGet()
        }

        fun recordReservationConfirmed(revenue: Double, processingTime: Duration) {
            reservationsByStatus[ReservationStatus.HOLD]?.decrementAndGet()
            reservationsByStatus[ReservationStatus.CONFIRMED]?.incrementAndGet()
            
            // Update revenue
            totalRevenue.updateAndGet { current -> current + revenue }
            
            // Update processing time
            synchronized(processingTimes) {
                processingTimes.add(processingTime)
                if (processingTimes.size > 1000) {
                    processingTimes.removeAt(0) // Keep only last 1000 entries
                }
                val average = processingTimes.map { it.toMillis() }.average()
                averageProcessingTime.set(Duration.ofMillis(average.toLong()))
            }
            
            updateConversionRate()
        }

        fun recordReservationCancelled() {
            reservationsByStatus[ReservationStatus.HOLD]?.decrementAndGet()
            reservationsByStatus[ReservationStatus.CANCELLED]?.incrementAndGet()
            activeReservations.decrementAndGet()
            updateConversionRate()
        }

        fun recordReservationExpired() {
            reservationsByStatus[ReservationStatus.HOLD]?.decrementAndGet()
            reservationsByStatus[ReservationStatus.EXPIRED]?.incrementAndGet()
            activeReservations.decrementAndGet()
            updateConversionRate()
        }

        private fun updateConversionRate() {
            val confirmed = reservationsByStatus[ReservationStatus.CONFIRMED]?.get() ?: 0
            val total = reservationsByStatus.values.sumOf { it.get() }
            val rate = if (total > 0) confirmed.toDouble() / total.toDouble() else 0.0
            conversionRate.set(rate)
        }

        fun getActiveReservations(): Long = activeReservations.get()
        fun getTotalRevenue(): Double = totalRevenue.get()
        fun getConversionRate(): Double = conversionRate.get()
    }

    @Configuration
    class BusinessMetricsCollector(
        @Autowired private val meterRegistry: MeterRegistry,
        @Autowired private val reservationMetrics: ReservationMetrics
    ) {

        @Scheduled(fixedRate = 60000) // Every minute
        fun collectBusinessMetrics() {
            try {
                // Record business KPIs
                recordPerformanceMetrics()
                recordSLAMetrics()
                recordCapacityMetrics()
            } catch (e: Exception) {
                // Log error but don't fail the application
                org.slf4j.LoggerFactory.getLogger(BusinessMetricsCollector::class.java)
                    .warn("Failed to collect business metrics", e)
            }
        }

        private fun recordPerformanceMetrics() {
            // Track throughput
            val httpRequests = meterRegistry.find("http.server.requests")
                .counter()?.count() ?: 0.0
            
            meterRegistry.gauge("reservation.throughput.requests_per_minute", httpRequests)

            // Track error rates
            val errors = meterRegistry.find("http.server.requests")
                .tag("status", "5xx")
                .counter()?.count() ?: 0.0
            
            val errorRate = if (httpRequests > 0) (errors / httpRequests) * 100 else 0.0
            meterRegistry.gauge("reservation.error_rate.percentage", errorRate)
        }

        private fun recordSLAMetrics() {
            // Track P95 response times
            val p95Timer = meterRegistry.find("http.server.requests")
                .timer()
            
            p95Timer?.let { timer ->
                val p95 = timer.percentile(0.95)
                meterRegistry.gauge("reservation.sla.response_time.p95", p95)
                
                // Record SLA compliance (< 1 second for 95% of requests)
                val slaCompliance = if (p95 < 1.0) 1.0 else 0.0
                meterRegistry.gauge("reservation.sla.compliance", slaCompliance)
            }
        }

        private fun recordCapacityMetrics() {
            // Track capacity utilization
            val activeReservations = reservationMetrics.getActiveReservations()
            val maxCapacity = 10000.0 // Configure based on your capacity planning
            val utilization = (activeReservations / maxCapacity) * 100
            
            meterRegistry.gauge("reservation.capacity.utilization", utilization)
            
            // Track revenue metrics
            val totalRevenue = reservationMetrics.getTotalRevenue()
            meterRegistry.gauge("reservation.revenue.total", totalRevenue)
            
            // Track conversion funnel
            val conversionRate = reservationMetrics.getConversionRate()
            meterRegistry.gauge("reservation.funnel.conversion_rate", conversionRate * 100) // As percentage
        }
    }
}