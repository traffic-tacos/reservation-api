package com.traffictacos.reservation.performance

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.stats.CacheStats
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CompletableFuture

@Configuration
class CacheConfiguration {

    @Bean
    fun reservationCache(meterRegistry: MeterRegistry): Cache<String, Any> {
        val cache = Caffeine.newBuilder()
            .maximumSize(10000) // Max 10k reservations in cache
            .expireAfterWrite(Duration.ofMinutes(15)) // 15 min TTL
            .expireAfterAccess(Duration.ofMinutes(5)) // 5 min idle timeout
            .recordStats() // Enable statistics
            .build<String, Any>()

        // Register cache metrics with Micrometer
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "reservation")
        return cache
    }

    @Bean
    fun idempotencyCache(meterRegistry: MeterRegistry): Cache<String, Any> {
        val cache = Caffeine.newBuilder()
            .maximumSize(50000) // Max 50k idempotency records
            .expireAfterWrite(Duration.ofMinutes(5)) // 5 min TTL (matches service TTL)
            .recordStats()
            .build<String, Any>()

        CaffeineCacheMetrics.monitor(meterRegistry, cache, "idempotency")
        return cache
    }

    @Bean
    fun inventoryCache(meterRegistry: MeterRegistry): Cache<String, Any> {
        val cache = Caffeine.newBuilder()
            .maximumSize(20000) // Max 20k inventory checks
            .expireAfterWrite(Duration.ofSeconds(30)) // 30 sec TTL (short for accuracy)
            .expireAfterAccess(Duration.ofSeconds(10)) // 10 sec idle timeout
            .recordStats()
            .build<String, Any>()

        CaffeineCacheMetrics.monitor(meterRegistry, cache, "inventory")
        return cache
    }

    @Bean
    fun eventCache(meterRegistry: MeterRegistry): Cache<String, String> {
        val cache = Caffeine.newBuilder()
            .maximumSize(5000) // Max 5k event metadata
            .expireAfterWrite(Duration.ofHours(1)) // 1 hour TTL
            .recordStats()
            .build<String, String>()

        CaffeineCacheMetrics.monitor(meterRegistry, cache, "event")
        return cache
    }
}

@Configuration
class CacheService(
    private val reservationCache: Cache<String, Any>,
    private val idempotencyCache: Cache<String, Any>,
    private val inventoryCache: Cache<String, Any>,
    private val eventCache: Cache<String, String>,
    private val meterRegistry: MeterRegistry
) {

    /**
     * Cache-aside pattern for reservations
     */
    fun <T> cacheReservation(key: String, supplier: () -> Mono<T>): Mono<T> {
        return Mono.fromCallable {
            @Suppress("UNCHECKED_CAST")
            reservationCache.getIfPresent(key) as? T
        }.cast(Any::class.java)
            .switchIfEmpty(
                supplier().cast(Any::class.java).doOnNext { value ->
                    reservationCache.put(key, value)
                    meterRegistry.counter("cache.reservation.miss").increment()
                }
            )
            .doOnNext { 
                if (reservationCache.getIfPresent(key) != null) {
                    meterRegistry.counter("cache.reservation.hit").increment()
                }
            }
            .cast(Any::class.java)
            .map { it as T }
    }

    /**
     * Cache idempotency records with async loading
     */
    fun cacheIdempotency(key: String, supplier: () -> Mono<Any>): Mono<Any> {
        return Mono.fromCallable {
            idempotencyCache.getIfPresent(key)
        }.switchIfEmpty(
            supplier().doOnNext { value ->
                idempotencyCache.put(key, value)
                meterRegistry.counter("cache.idempotency.miss").increment()
            }
        ).doOnNext { 
            if (idempotencyCache.getIfPresent(key) != null) {
                meterRegistry.counter("cache.idempotency.hit").increment()
            }
        }
    }

    /**
     * Cache inventory availability with short TTL
     */
    fun cacheInventoryCheck(eventId: String, seatIds: List<String>, supplier: () -> Mono<Any>): Mono<Any> {
        val key = "inventory:$eventId:${seatIds.sorted().joinToString(",")}"
        
        return Mono.fromCallable {
            inventoryCache.getIfPresent(key)
        }.switchIfEmpty(
            supplier().doOnNext { value ->
                inventoryCache.put(key, value)
                meterRegistry.counter("cache.inventory.miss", "event", eventId).increment()
            }
        ).doOnNext { 
            if (inventoryCache.getIfPresent(key) != null) {
                meterRegistry.counter("cache.inventory.hit", "event", eventId).increment()
            }
        }
    }

    /**
     * Cache event metadata
     */
    fun cacheEventMetadata(eventId: String, supplier: () -> String): String? {
        return eventCache.get(eventId) { 
            meterRegistry.counter("cache.event.miss").increment()
            supplier()
        }.also {
            meterRegistry.counter("cache.event.hit").increment()
        }
    }

    /**
     * Invalidate cache entries
     */
    fun invalidateReservation(reservationId: String) {
        reservationCache.invalidate("reservation:$reservationId")
        meterRegistry.counter("cache.reservation.invalidate").increment()
    }

    fun invalidateIdempotency(idempotencyKey: String) {
        idempotencyCache.invalidate(idempotencyKey)
        meterRegistry.counter("cache.idempotency.invalidate").increment()
    }

    fun invalidateInventory(eventId: String) {
        // Invalidate all entries for this event
        inventoryCache.asMap().keys.filter { it.startsWith("inventory:$eventId:") }
            .forEach { 
                inventoryCache.invalidate(it)
                meterRegistry.counter("cache.inventory.invalidate", "event", eventId).increment()
            }
    }

    /**
     * Get cache statistics for monitoring
     */
    fun getCacheStats(): Map<String, CacheStats> {
        return mapOf(
            "reservation" to reservationCache.stats(),
            "idempotency" to idempotencyCache.stats(),
            "inventory" to inventoryCache.stats(),
            "event" to eventCache.stats()
        )
    }

    /**
     * Warm up caches with frequently accessed data
     */
    fun warmUpCaches() {
        // This would typically be called on startup
        // Load frequently accessed reservations, events, etc.
        meterRegistry.counter("cache.warmup.executed").increment()
    }

    /**
     * Clear all caches (for testing or maintenance)
     */
    fun clearAllCaches() {
        reservationCache.invalidateAll()
        idempotencyCache.invalidateAll()
        inventoryCache.invalidateAll()
        eventCache.invalidateAll()
        meterRegistry.counter("cache.clear.all").increment()
    }

    /**
     * Preload cache with async data
     */
    fun preloadCache(keys: List<String>, loader: (String) -> CompletableFuture<Any>) {
        val futures = keys.map { key ->
            loader(key).thenAccept { value ->
                reservationCache.put(key, value)
            }
        }
        
        CompletableFuture.allOf(*futures.toTypedArray()).thenRun {
            meterRegistry.counter("cache.preload.completed", "count", keys.size.toString()).increment()
        }
    }
}