package com.banking.notificationservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI notificationServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Notification Service API")
                        .description("Stores and serves in-app notifications. " +
                                     "EMAIL and SMS channels are stored but not yet dispatched — deferred until SMTP/Twilio integration.")
                        .version("1.0.0"));
    }
}
