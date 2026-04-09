package com.banking.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/*
 * No @EnableDiscoveryClient or @EnableEurekaClient needed.
 * Since Spring Cloud 2020, having spring-cloud-starter-netflix-eureka-client
 * on the classpath is sufficient — autoconfiguration registers this service
 * with Eureka automatically on startup.
 *
 * No @EnableGateway either — spring-cloud-starter-gateway autoconfigures
 * the route engine, predicate factories, and filter factories from the
 * routes defined in application.yml.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
