package com.banking.authservice;

import com.banking.authservice.dto.LoginRequest;
import com.banking.authservice.dto.RegisterRequest;
import com.banking.authservice.repository.CredentialRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for auth-service.
 *
 * WireMock stubs user-service so auth-service's Feign call to POST /users
 * returns a predictable response without needing a real user-service.
 *
 * Key test scenarios:
 * - register: BCrypt hash stored, user profile created via Feign, JWT issued
 * - login: BCrypt.matches() called on stored hash, JWT issued on success
 * - duplicate email: rejected before Feign call (auth DB check first)
 * - wrong password: same 401 as wrong email (prevents user enumeration)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.openfeign.circuitbreaker.enabled=false"
        }
)
@Testcontainers
class AuthControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // WireMock started before Spring context; URL registered via @DynamicPropertySource
    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        wireMock.start();
        // Point Feign's "user-service" client at WireMock instead of Eureka
        registry.add("feign.client.config.user-service.url", wireMock::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CredentialRepository credentialRepository;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        credentialRepository.deleteAll();
    }

    // ─── REGISTER ──────────────────────────────────────────────────────────────

    @Test
    void register_validRequest_returns201WithToken() {
        // Stub user-service: POST /users → 201 with user profile
        wireMock.stubFor(post(urlEqualTo("/users"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": 1,
                                  "firstName": "John",
                                  "lastName": "Doe",
                                  "email": "john@example.com",
                                  "status": "ACTIVE",
                                  "createdAt": "2026-01-01T00:00:00"
                                }""")));

        var request = new RegisterRequest("John", "Doe", "john@example.com", "password123");
        ResponseEntity<Map> response = rest.postForEntity("/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("token");
        assertThat(response.getBody().get("token")).isNotNull();
        assertThat(response.getBody()).containsEntry("tokenType", "Bearer");
        assertThat(response.getBody()).containsEntry("email", "john@example.com");

        // Credential stored in DB with BCrypt hash (not plaintext)
        assertThat(credentialRepository.existsByEmail("john@example.com")).isTrue();
        var saved = credentialRepository.findByEmail("john@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).doesNotContain("password123"); // hash, not plaintext
        assertThat(saved.getPasswordHash()).startsWith("$2a$");            // BCrypt prefix
    }

    @Test
    void register_duplicateEmail_returns409() {
        wireMock.stubFor(post(urlEqualTo("/users"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":1,"firstName":"Jane","lastName":"Doe",
                                 "email":"jane@example.com","status":"ACTIVE",
                                 "createdAt":"2026-01-01T00:00:00"}""")));

        var request = new RegisterRequest("Jane", "Doe", "jane@example.com", "password123");
        rest.postForEntity("/auth/register", request, Map.class); // first registration

        // Second registration with same email — rejected before Feign call
        ResponseEntity<Map> response = rest.postForEntity("/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // WireMock should have been called only once (first register)
        wireMock.verify(1, postRequestedFor(urlEqualTo("/users")));
    }

    @Test
    void register_shortPassword_returns400() {
        var request = new RegisterRequest("Short", "Pass", "short@example.com", "1234567"); // 7 chars, min is 8

        ResponseEntity<Map> response = rest.postForEntity("/auth/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/users"))); // Feign never called
    }

    // ─── LOGIN ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithToken() {
        // First register to create a credential
        wireMock.stubFor(post(urlEqualTo("/users"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":2,"firstName":"Alice","lastName":"Smith",
                                 "email":"alice@example.com","status":"ACTIVE",
                                 "createdAt":"2026-01-01T00:00:00"}""")));

        rest.postForEntity("/auth/register",
                new RegisterRequest("Alice", "Smith", "alice@example.com", "securepass"), Map.class);

        // Now login
        var loginReq = new LoginRequest("alice@example.com", "securepass");
        ResponseEntity<Map> response = rest.postForEntity("/auth/login", loginReq, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("token");
        assertThat(response.getBody().get("token")).isNotNull();
    }

    @Test
    void login_wrongPassword_returns401() {
        // Register first
        wireMock.stubFor(post(urlEqualTo("/users"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":3,"firstName":"Bob","lastName":"Brown",
                                 "email":"bob@example.com","status":"ACTIVE",
                                 "createdAt":"2026-01-01T00:00:00"}""")));

        rest.postForEntity("/auth/register",
                new RegisterRequest("Bob", "Brown", "bob@example.com", "correctpass"), Map.class);

        var loginReq = new LoginRequest("bob@example.com", "wrongpassword");
        ResponseEntity<Map> response = rest.postForEntity("/auth/login", loginReq, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_unknownEmail_returns401() {
        var loginReq = new LoginRequest("nobody@example.com", "password123");
        ResponseEntity<Map> response = rest.postForEntity("/auth/login", loginReq, Map.class);

        // Same 401 as wrong password — prevents user enumeration
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
