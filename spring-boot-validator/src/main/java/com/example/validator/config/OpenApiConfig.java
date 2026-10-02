package com.example.validator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Serverless Orders Pipeline & Validator API")
                .version("1.0.0")
                .description("Interactive OpenAPI / Swagger documentation for validating the serverless event-driven architecture (API Gateway, SQS, DynamoDB, SNS) against the local Moto emulator.")
                .contact(new Contact()
                    .name("Engineering Team")
                    .email("dev@example.com"))
                .license(new License()
                    .name("Apache 2.0")
                    .url("https://springdoc.org")))
            .servers(List.of(
                new Server().url("http://localhost:8080").description("Local Validator Service"),
                new Server().url("http://127.0.0.1:5000").description("Moto AWS Server")
            ));
    }
}
