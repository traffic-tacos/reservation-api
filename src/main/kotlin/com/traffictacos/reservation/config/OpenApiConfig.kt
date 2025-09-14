package com.traffictacos.reservation.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Contact
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.annotations.servers.Server
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.License
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(
        title = "Traffic Tacos Reservation API",
        description = "High-performance ticket reservation service for Traffic Tacos platform",
        version = "1.0.0",
        contact = Contact(
            name = "Traffic Tacos Development Team",
            email = "dev@traffictacos.com"
        )
    ),
    servers = [
        Server(url = "http://localhost:8080", description = "Local development server"),
        Server(url = "https://api.traffictacos.com", description = "Production server")
    ]
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "JWT Authorization header using the Bearer scheme"
)
class OpenApiConfig {

    @Bean
    fun publicApi(): GroupedOpenApi {
        return GroupedOpenApi.builder()
            .group("reservation-api")
            .pathsToMatch("/v1/**")
            .build()
    }

    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                io.swagger.v3.oas.models.info.Info()
                    .title("Traffic Tacos Reservation API")
                    .description("High-performance ticket reservation service for Traffic Tacos platform")
                    .version("1.0.0")
                    .contact(
                        io.swagger.v3.oas.models.info.Contact()
                            .name("Traffic Tacos Development Team")
                            .email("dev@traffictacos.com")
                    )
                    .license(
                        License()
                            .name("Apache 2.0")
                            .url("https://www.apache.org/licenses/LICENSE-2.0")
                    )
            )
    }
}
