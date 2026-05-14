package com.banking.userservice;

import com.banking.userservice.dto.CreateUserRequest;
import com.banking.userservice.dto.UpdateStatusRequest;
import com.banking.userservice.entity.UserStatus;
import com.banking.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for user-service.
 *
 * Uses a real PostgreSQL container via Testcontainers. Flyway migrations run on
 * startup so the schema matches the entity model exactly. Each test starts with
 * an empty table (cleared in @BeforeEach) for isolation.
 *
 * @SpringBootTest(webEnvironment = RANDOM_PORT) boots the full application on a
 * random port. TestRestTemplate makes real HTTP calls — tests exercise the full
 * request/response cycle: controller → service → repository → DB.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                // Disable Zipkin reporting in tests — no Zipkin server available
                "management.tracing.enabled=false",
                "spring.zipkin.enabled=false"
        }
)
@Testcontainers
class UserControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    // ─── CREATE ────────────────────────────────────────────────────────────────

    @Test
    void createUser_validRequest_returns201() {
        var request = new CreateUserRequest("John", "Doe", "john.doe@example.com");

        ResponseEntity<Map> response = rest.postForEntity("/users", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("email", "john.doe@example.com");
        assertThat(response.getBody()).containsEntry("status", "ACTIVE");
        assertThat(response.getBody()).containsKey("id");
    }

    @Test
    void createUser_duplicateEmail_returns409() {
        var request = new CreateUserRequest("John", "Doe", "duplicate@example.com");
        rest.postForEntity("/users", request, Map.class);

        ResponseEntity<Map> response = rest.postForEntity("/users", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createUser_blankFirstName_returns400() {
        var request = new CreateUserRequest("", "Doe", "valid@example.com");

        ResponseEntity<Map> response = rest.postForEntity("/users", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createUser_invalidEmail_returns400() {
        var request = new CreateUserRequest("John", "Doe", "not-an-email");

        ResponseEntity<Map> response = rest.postForEntity("/users", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── READ ──────────────────────────────────────────────────────────────────

    @Test
    void getUserById_existingUser_returns200() {
        var created = rest.postForEntity("/users", new CreateUserRequest("Alice", "Smith", "alice@example.com"), Map.class);
        Long id = ((Number) created.getBody().get("id")).longValue();

        ResponseEntity<Map> response = rest.getForEntity("/users/" + id, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("email", "alice@example.com");
    }

    @Test
    void getUserById_notFound_returns404() {
        ResponseEntity<Map> response = rest.getForEntity("/users/999999", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getUserByEmail_existingEmail_returns200() {
        rest.postForEntity("/users", new CreateUserRequest("Bob", "Jones", "bob@example.com"), Map.class);

        ResponseEntity<Map> response = rest.getForEntity("/users/email/bob@example.com", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("firstName", "Bob");
    }

    @Test
    void getAllUsers_returns200WithPaginatedResponse() {
        rest.postForEntity("/users", new CreateUserRequest("User1", "A", "user1@example.com"), Map.class);
        rest.postForEntity("/users", new CreateUserRequest("User2", "B", "user2@example.com"), Map.class);

        ResponseEntity<Map> response = rest.getForEntity("/users?page=0&size=10", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("content");
        assertThat(response.getBody()).containsKey("totalElements");
        assertThat(((Number) response.getBody().get("totalElements")).intValue()).isEqualTo(2);
    }

    // ─── UPDATE ────────────────────────────────────────────────────────────────

    @Test
    void updateStatus_toSuspended_returns200() {
        var created = rest.postForEntity("/users", new CreateUserRequest("Carol", "White", "carol@example.com"), Map.class);
        Long id = ((Number) created.getBody().get("id")).longValue();
        var statusUpdate = new UpdateStatusRequest(UserStatus.SUSPENDED);

        ResponseEntity<Map> response = rest.exchange(
                "/users/" + id + "/status",
                HttpMethod.PUT,
                new HttpEntity<>(statusUpdate),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "SUSPENDED");
    }

    // ─── DELETE ────────────────────────────────────────────────────────────────

    @Test
    void deleteUser_existingUser_returns204AndSoftDeletes() {
        var created = rest.postForEntity("/users", new CreateUserRequest("Dave", "Black", "dave@example.com"), Map.class);
        Long id = ((Number) created.getBody().get("id")).longValue();

        ResponseEntity<Void> deleteResponse = rest.exchange("/users/" + id, HttpMethod.DELETE, null, Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Row still exists — soft delete, not hard delete
        assertThat(userRepository.findById(id)).isPresent();
        assertThat(userRepository.findById(id).get().getStatus()).isEqualTo(UserStatus.DELETED);
    }
}
