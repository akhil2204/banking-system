package com.banking.transactionservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI transactionServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Transaction Service API")
                        .description("Processes debit, credit, and transfer operations. " +
                                     "All write operations persist a PENDING record before execution; " +
                                     "check the 'status' field in the response for the outcome.")
                        .version("1.0.0"));
    }
}
