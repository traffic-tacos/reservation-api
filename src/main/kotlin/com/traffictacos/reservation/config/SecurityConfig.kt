package com.traffictacos.reservation.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    // Public endpoints
                    .pathMatchers("/actuator/**").permitAll()
                    .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/webjars/**").permitAll()
                    .pathMatchers("/health", "/info", "/metrics").permitAll()

                    // API endpoints require authentication
                    .pathMatchers("/v1/**").authenticated()

                    // All other requests require authentication
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtDecoder(jwtDecoder())
                }
            }
            .build()
    }

    @Bean
    fun jwtDecoder(): org.springframework.security.oauth2.jwt.ReactiveJwtDecoder {
        // For development/testing, you might want to use a mock decoder
        // In production, this should point to your actual JWT issuer
        return org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
            .withJwkSetUri("https://your-auth-server.com/.well-known/jwks.json")
            .build()
    }
}