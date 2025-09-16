package com.traffictacos.reservation.config

import com.traffictacos.reservation.observability.StructuredLoggingConfiguration
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.security.web.server.header.ContentTypeOptionsServerHttpHeadersWriter
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    @Profile("local")
    fun localSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .cors { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    .anyExchange().permitAll()
            }
            .build()
    }

    @Bean
    @Profile("!local")
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() } // Disabled for stateless API
            .cors { cors ->
                cors.configurationSource { request ->
                    val config = org.springframework.web.cors.CorsConfiguration()
                    config.allowedOriginPatterns = listOf("https://*.traffictacos.com", "https://localhost:*")
                    config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    config.allowedHeaders = listOf("*")
                    config.allowCredentials = true
                    config.maxAge = Duration.ofMinutes(30).seconds
                    config
                }
            }
            .headers { headers ->
                headers
                    .frameOptions { it.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY) }
                    .contentTypeOptions { it.mode(ContentTypeOptionsServerHttpHeadersWriter.Mode.NOSNIFF) }
                    .httpStrictTransportSecurity { hstsConfig ->
                        hstsConfig
                            .maxAgeInSeconds(31536000) // 1 year
                            .includeSubdomains(true)
                            .preload(true)
                    }
                    .referrerPolicy { it.policy(org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
                    .and()
                    .cache { it.disable() } // Disable caching for security
            }
            .authorizeExchange { exchanges ->
                exchanges
                    // Public endpoints (no authentication required)
                    .pathMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .pathMatchers(HttpMethod.GET, "/actuator/info").permitAll()
                    
                    // Metrics endpoints require authentication
                    .pathMatchers(HttpMethod.GET, "/actuator/metrics").hasRole("ADMIN")
                    .pathMatchers(HttpMethod.GET, "/actuator/prometheus").hasRole("ADMIN")
                    .pathMatchers(HttpMethod.GET, "/actuator/**").hasRole("ADMIN")
                    
                    // API documentation
                    .pathMatchers(HttpMethod.GET, "/v3/api-docs/**").hasAnyRole("USER", "ADMIN")
                    .pathMatchers(HttpMethod.GET, "/swagger-ui/**").hasAnyRole("USER", "ADMIN")
                    
                    // Reservation endpoints
                    .pathMatchers(HttpMethod.POST, "/v1/reservations").hasAnyRole("USER", "ADMIN")
                    .pathMatchers(HttpMethod.GET, "/v1/reservations/**").hasAnyRole("USER", "ADMIN")
                    .pathMatchers(HttpMethod.POST, "/v1/reservations/*/confirm").hasAnyRole("USER", "ADMIN")
                    .pathMatchers(HttpMethod.POST, "/v1/reservations/*/cancel").hasAnyRole("USER", "ADMIN")

                    // All other endpoints require authentication
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2
                    .jwt { jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                    }
                    .authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(accessDeniedHandler())
            }
            .build()
    }

    @Bean
    @Profile("!local")
    fun jwtAuthenticationConverter(): ReactiveJwtAuthenticationConverterAdapter {
        val jwtAuthenticationConverter = JwtAuthenticationConverter()

        // Extract roles from JWT token
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter { jwt ->
            val realmAccess = jwt.getClaim<Map<String, Any>>("realm_access")
            val roles = realmAccess?.get("roles") as? List<String> ?: emptyList()

            roles.map { role -> "ROLE_$role" }
                .map { org.springframework.security.core.authority.SimpleGrantedAuthority(it) }
        }

        return ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter)
    }

    @Bean
    @Profile("!local")
    fun authenticationEntryPoint(): org.springframework.security.web.server.ServerAuthenticationEntryPoint {
        return org.springframework.security.web.server.ServerAuthenticationEntryPoint { exchange, _ ->
            exchange.response.statusCode = org.springframework.http.HttpStatus.UNAUTHORIZED
            exchange.response.headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON

            val errorResponse = """
                {
                    "error": {
                        "code": "UNAUTHENTICATED",
                        "message": "Authentication is required to access this resource"
                    }
                }
            """.trimIndent()

            val buffer = exchange.response.bufferFactory().wrap(errorResponse.toByteArray())
            exchange.response.writeWith(Mono.just(buffer))
        }
    }

    @Bean
    @Profile("!local")
    fun accessDeniedHandler(): ServerAccessDeniedHandler {
        return ServerAccessDeniedHandler { exchange, _ ->
            exchange.response.statusCode = org.springframework.http.HttpStatus.FORBIDDEN
            exchange.response.headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON

            val errorResponse = """
                {
                    "error": {
                        "code": "FORBIDDEN",
                        "message": "Access denied to this resource"
                    }
                }
            """.trimIndent()

            val buffer = exchange.response.bufferFactory().wrap(errorResponse.toByteArray())
            exchange.response.writeWith(Mono.just(buffer))
        }
    }

    @Bean
    @Profile("!local")
    fun securityAuditFilter(
        meterRegistry: MeterRegistry,
        structuredLogging: StructuredLoggingConfiguration
    ): WebFilter {
        return SecurityAuditFilter(meterRegistry, structuredLogging)
    }

    @Bean
    @Profile("!local")
    fun requestValidationFilter(): WebFilter {
        return RequestValidationFilter()
    }

    @Bean
    @Profile("!local")
    fun rateLimitingFilter(meterRegistry: MeterRegistry): WebFilter {
        return RateLimitingFilter(meterRegistry)
    }
}

/**
 * Security audit filter for monitoring and logging security events
 */
class SecurityAuditFilter(
    private val meterRegistry: MeterRegistry,
    private val structuredLogging: StructuredLoggingConfiguration
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val startTime = Instant.now()
        val request = exchange.request
        val clientIp = getClientIp(exchange)
        val userAgent = request.headers.getFirst(HttpHeaders.USER_AGENT) ?: "unknown"
        
        return chain.filter(exchange)
            .doOnSuccess {
                val duration = Duration.between(startTime, Instant.now())
                val statusCode = exchange.response.statusCode?.value() ?: 0
                
                // Record security metrics
                meterRegistry.counter("security.requests.total",
                    "method", request.method.name(),
                    "status", statusCode.toString(),
                    "endpoint", request.path.pathWithinApplication().value()
                ).increment()
                
                // Log security events
                if (statusCode == 401) {
                    structuredLogging.logSecurityEvent(
                        "AUTHENTICATION_FAILURE",
                        extractUserId(exchange),
                        mapOf(
                            "ip" to clientIp,
                            "user_agent" to userAgent,
                            "endpoint" to request.path.pathWithinApplication().value(),
                            "method" to request.method.name()
                        )
                    )
                    meterRegistry.counter("security.authentication.failures").increment()
                } else if (statusCode == 403) {
                    structuredLogging.logSecurityEvent(
                        "AUTHORIZATION_FAILURE",
                        extractUserId(exchange),
                        mapOf(
                            "ip" to clientIp,
                            "user_agent" to userAgent,
                            "endpoint" to request.path.pathWithinApplication().value(),
                            "method" to request.method.name()
                        )
                    )
                    meterRegistry.counter("security.authorization.failures").increment()
                }
                
                // Monitor suspicious patterns
                detectSuspiciousActivity(exchange, clientIp, userAgent, statusCode)
            }
            .doOnError { error ->
                structuredLogging.logSecurityEvent(
                    "REQUEST_ERROR",
                    extractUserId(exchange),
                    mapOf(
                        "ip" to clientIp,
                        "error" to error.message,
                        "endpoint" to request.path.pathWithinApplication().value()
                    )
                )
            }
    }

    private fun getClientIp(exchange: ServerWebExchange): String {
        val request = exchange.request
        return request.headers.getFirst("X-Forwarded-For")
            ?: request.headers.getFirst("X-Real-IP")
            ?: request.remoteAddress?.address?.hostAddress
            ?: "unknown"
    }

    private fun extractUserId(exchange: ServerWebExchange): String? {
        return exchange.getPrincipal<org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken>()
            .cast(org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken::class.java)
            .map { it.token.subject }
            .block()
    }

    private fun detectSuspiciousActivity(exchange: ServerWebExchange, clientIp: String, userAgent: String, statusCode: Int) {
        // Detect potential attack patterns
        val request = exchange.request
        val path = request.path.pathWithinApplication().value()
        
        // SQL injection patterns
        if (containsSqlInjectionPatterns(path)) {
            structuredLogging.logSecurityEvent(
                "POTENTIAL_SQL_INJECTION",
                extractUserId(exchange),
                mapOf("ip" to clientIp, "path" to path)
            )
            meterRegistry.counter("security.threats.sql_injection").increment()
        }
        
        // XSS patterns
        if (containsXssPatterns(path)) {
            structuredLogging.logSecurityEvent(
                "POTENTIAL_XSS",
                extractUserId(exchange),
                mapOf("ip" to clientIp, "path" to path)
            )
            meterRegistry.counter("security.threats.xss").increment()
        }
        
        // Path traversal
        if (containsPathTraversalPatterns(path)) {
            structuredLogging.logSecurityEvent(
                "POTENTIAL_PATH_TRAVERSAL",
                extractUserId(exchange),
                mapOf("ip" to clientIp, "path" to path)
            )
            meterRegistry.counter("security.threats.path_traversal").increment()
        }
        
        // Suspicious user agents
        if (isSuspiciousUserAgent(userAgent)) {
            structuredLogging.logSecurityEvent(
                "SUSPICIOUS_USER_AGENT",
                extractUserId(exchange),
                mapOf("ip" to clientIp, "user_agent" to userAgent)
            )
            meterRegistry.counter("security.threats.suspicious_user_agent").increment()
        }
    }

    private fun containsSqlInjectionPatterns(input: String): Boolean {
        val patterns = listOf("'", "\"", ";", "--", "/*", "*/", "xp_", "sp_", "UNION", "SELECT", "DROP")
        return patterns.any { input.uppercase().contains(it.uppercase()) }
    }

    private fun containsXssPatterns(input: String): Boolean {
        val patterns = listOf("<script", "</script>", "javascript:", "onload=", "onerror=")
        return patterns.any { input.uppercase().contains(it.uppercase()) }
    }

    private fun containsPathTraversalPatterns(input: String): Boolean {
        val patterns = listOf("../", "..\\", "%2e%2e", "%252e%252e")
        return patterns.any { input.contains(it, ignoreCase = true) }
    }

    private fun isSuspiciousUserAgent(userAgent: String): Boolean {
        val suspiciousAgents = listOf("sqlmap", "nikto", "burp", "nmap", "scanner", "bot")
        return suspiciousAgents.any { userAgent.lowercase().contains(it) }
    }
}

/**
 * Request validation filter for input sanitization
 */
class RequestValidationFilter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        
        // Validate Content-Length
        val contentLength = request.headers.contentLength
        if (contentLength > MAX_REQUEST_SIZE) {
            return writeErrorResponse(exchange, "Request too large", 413)
        }
        
        // Validate Content-Type for POST/PUT requests
        if (request.method in listOf(org.springframework.http.HttpMethod.POST, org.springframework.http.HttpMethod.PUT)) {
            val contentType = request.headers.contentType
            if (contentType == null || !isAllowedContentType(contentType)) {
                return writeErrorResponse(exchange, "Unsupported Content-Type", 415)
            }
        }
        
        // Validate headers
        if (hasInvalidHeaders(request.headers)) {
            return writeErrorResponse(exchange, "Invalid headers", 400)
        }
        
        return chain.filter(exchange)
    }

    private fun isAllowedContentType(contentType: org.springframework.http.MediaType): Boolean {
        return contentType.isCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON) ||
               contentType.isCompatibleWith(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED) ||
               contentType.isCompatibleWith(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
    }

    private fun hasInvalidHeaders(headers: org.springframework.http.HttpHeaders): Boolean {
        // Check for suspicious headers
        return headers.any { (name, values) ->
            name.lowercase().contains("x-forwarded") && values.any { it.contains("..") }
        }
    }

    private fun writeErrorResponse(exchange: ServerWebExchange, message: String, statusCode: Int): Mono<Void> {
        exchange.response.statusCode = org.springframework.http.HttpStatus.valueOf(statusCode)
        exchange.response.headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON
        
        val errorResponse = """{"error": {"code": "VALIDATION_ERROR", "message": "$message"}}"""
        val buffer = exchange.response.bufferFactory().wrap(errorResponse.toByteArray())
        return exchange.response.writeWith(Mono.just(buffer))
    }

    companion object {
        private const val MAX_REQUEST_SIZE = 1024 * 1024 // 1MB
    }
}

/**
 * Rate limiting filter
 */
class RateLimitingFilter(
    private val meterRegistry: MeterRegistry
) : WebFilter {

    private val rateLimitCache = mutableMapOf<String, RateLimitInfo>()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val clientIp = getClientIp(exchange)
        val currentTime = Instant.now()
        
        synchronized(rateLimitCache) {
            val rateLimitInfo = rateLimitCache.getOrPut(clientIp) { 
                RateLimitInfo(currentTime, 0) 
            }
            
            // Reset counter if window has passed
            if (Duration.between(rateLimitInfo.windowStart, currentTime).toMinutes() >= 1) {
                rateLimitInfo.windowStart = currentTime
                rateLimitInfo.requestCount = 0
            }
            
            rateLimitInfo.requestCount++
            
            if (rateLimitInfo.requestCount > MAX_REQUESTS_PER_MINUTE) {
                meterRegistry.counter("security.rate_limit.exceeded", "ip", clientIp).increment()
                return writeRateLimitResponse(exchange)
            }
        }
        
        return chain.filter(exchange)
    }

    private fun getClientIp(exchange: ServerWebExchange): String {
        val request = exchange.request
        return request.headers.getFirst("X-Forwarded-For")
            ?: request.headers.getFirst("X-Real-IP")
            ?: request.remoteAddress?.address?.hostAddress
            ?: "unknown"
    }

    private fun writeRateLimitResponse(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
        exchange.response.headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON
        exchange.response.headers.add("Retry-After", "60")
        
        val errorResponse = """{"error": {"code": "RATE_LIMIT_EXCEEDED", "message": "Too many requests"}}"""
        val buffer = exchange.response.bufferFactory().wrap(errorResponse.toByteArray())
        return exchange.response.writeWith(Mono.just(buffer))
    }

    data class RateLimitInfo(var windowStart: Instant, var requestCount: Int)

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 100
    }
}
