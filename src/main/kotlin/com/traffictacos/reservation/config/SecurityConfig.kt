package com.traffictacos.reservation.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.security.web.server.authorization.ServerAuthenticationEntryPoint
import reactor.core.publisher.Mono

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .cors { it.disable() } // Configure CORS if needed

            .authorizeExchange { exchanges ->
                exchanges
                    // Public endpoints (no authentication required)
                    .pathMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .pathMatchers(HttpMethod.GET, "/actuator/info").permitAll()
                    .pathMatchers(HttpMethod.GET, "/actuator/metrics").permitAll()
                    .pathMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()

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
    fun authenticationEntryPoint(): ServerAuthenticationEntryPoint {
        return ServerAuthenticationEntryPoint { exchange, _ ->
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
}
