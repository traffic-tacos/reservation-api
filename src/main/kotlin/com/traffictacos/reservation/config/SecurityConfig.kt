package com.traffictacos.reservation.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    @Profile("local")
    fun localSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges.anyExchange().permitAll()
            }
            .build()
    }

    @Bean
    @Profile("!local")
    fun prodSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    // Public endpoints
                    .pathMatchers("/actuator/**").permitAll()
                    .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**").permitAll()
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
    @Profile("!local")
    fun jwtDecoder(): org.springframework.security.oauth2.jwt.ReactiveJwtDecoder {
        // For production, use the configured issuer URI
        return org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
            .withJwkSetUri("http://localhost:8080/auth/realms/test/protocol/openid-connect/certs")
            .build()
    }
}