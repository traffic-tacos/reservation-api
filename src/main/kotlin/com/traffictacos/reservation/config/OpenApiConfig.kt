package com.traffictacos.reservation.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.Components
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Traffic Tacos Reservation API")
                    .description("High-performance ticket reservation service handling 30k RPS")
                    .version("1.0.0")
                    .contact(
                        Contact()
                            .name("Traffic Tacos Team")
                            .email("support@traffictacos.com")
                            .url("https://traffictacos.com")
                    )
                    .license(
                        License()
                            .name("MIT License")
                            .url("https://opensource.org/licenses/MIT")
                    )
            )
            .servers(
                listOf(
                    Server()
                        .url("http://localhost:8010")
                        .description("Local development server (REST API)"),
                    Server()
                        .url("https://api.traffictacos.com")
                        .description("Production server")
                )
            )
            .components(
                Components()
                    .addSecuritySchemes("BearerAuth",
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description("JWT Bearer token authentication")
                    )
            )
            .addSecurityItem(SecurityRequirement().addList("BearerAuth"))
    }
}