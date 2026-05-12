package com.bablsoft.accessflow.security.internal.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "AccessFlow API", version = "v1",
                description = "Database access governance platform REST API"),
        security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
)
@SecurityScheme(
        name = OpenApiConfiguration.BEARER_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER,
        description = "Paste the JWT access token returned from POST /api/v1/auth/login (without the 'Bearer ' prefix)."
)
class OpenApiConfiguration {

    static final String BEARER_SCHEME = "bearerAuth";
}
