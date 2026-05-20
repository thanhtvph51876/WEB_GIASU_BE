package com.example.tutorplatform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI tutorPlatformOpenApi() {
    String scheme = "bearerAuth";
    return new OpenAPI()
        .info(new Info().title("Tutor Platform API").version("v1").description("Spring Boot backend cho nền tảng gia sư"))
        .components(new Components().addSecuritySchemes(scheme,
            new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(scheme));
  }
}
