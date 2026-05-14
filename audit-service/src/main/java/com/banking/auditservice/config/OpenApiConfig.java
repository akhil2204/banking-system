package com.banking.auditservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(info = @Info(
        title = "Audit Service API",
        version = "1.0",
        description = "Read-only audit log. All records written by Kafka consumers from transaction-service and account-service."
))
@Configuration
public class OpenApiConfig {}
