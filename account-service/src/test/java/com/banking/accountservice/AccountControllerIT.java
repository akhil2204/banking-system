package com.banking.accountservice;

import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.dto.DepositRequest;
import com.banking.accountservice.dto.WithdrawRequest;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for account-service.
 *
 * WireMock stubs user-service's GET /users/{id} endpoint so account-service's
 * Feign validation call returns a predictable response.
 *
 * Kafka publish (AuditEventPublisher) uses a non-existent bootstrap server in
 * the test environment. The publisher catches exceptions silently — this is
 * intentional: messaging failures must never affect the DB transaction.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.openfeign.circuitbreaker.enabled=false",
                // Kafka publishes fail silently (fire-and-forget pattern)
                "spring.kafka.bootstrap-servers=localhost:29092"
        }
)
@Testcontainers
class AccountControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("feign.client.config.user-service.url", wireMock::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        accountRepository.deleteAll();
    }

    private void stubActiveUser(Long userId) {
        wireMock.stubFor(get(urlEqualTo("/users/" + userId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": %d,
                                  "firstName": "John",
                                  "lastName": "Doe",
                                  "email": "john@example.com",
                                  "status": "ACTIVE"
                                }""".formatted(userId))));
    }

    private void stubUserNotFound(Long userId) {
        wireMock.stubFor(get(urlEqualTo("/users/" + userId))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"timestamp":"2026-01-01T00:00:00","status":404,
                                 "error":"Not Found","message":"User not found","path":"/users/%d"}
                                """.formatted(userId))));
    }

    // ─── CREATE ACCOUNT ────────────────────────────────────────────────────────

    @Test
    void createAccount_activeUser_returns201() {
        stubActiveUser(1L);

        var request = new CreateAccountRequest(1L, AccountType.SAVINGS);
        ResponseEntity<Map> response = rest.postForEntity("/accounts", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("userId", 1);
        assertThat(response.getBody()).containsEntry("type", "SAVINGS");
        assertThat(response.getBody()).containsEntry("status", "ACTIVE");
        assertThat(response.getBody()).containsKey("accountNumber");
        // Initial balance is 0 — no initialDeposit on creation
        assertThat(new BigDecimal(response.getBody().get("balance").toString()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createAccount_userNotFound_returns404() {
        stubUserNotFound(999L);

        var request = new CreateAccountRequest(999L, AccountType.CURRENT);
        ResponseEntity<Map> response = rest.postForEntity("/accounts", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createAccount_suspendedUser_returns422() {
        wireMock.stubFor(get(urlEqualTo("/users/2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":2,"firstName":"Suspended","lastName":"User",
                                 "email":"suspended@example.com","status":"SUSPENDED"}""")));

        var request = new CreateAccountRequest(2L, AccountType.SAVINGS);
        ResponseEntity<Map> response = rest.postForEntity("/accounts", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ─── DEPOSIT ───────────────────────────────────────────────────────────────

    @Test
    void deposit_validAmount_increasesBalance() {
        stubActiveUser(1L);
        var created = rest.postForEntity("/accounts",
                new CreateAccountRequest(1L, AccountType.SAVINGS), Map.class);
        Long accountId = ((Number) created.getBody().get("id")).longValue();

        var depositReq = new DepositRequest(new BigDecimal("500.00"));
        ResponseEntity<Map> response = rest.exchange(
                "/accounts/" + accountId + "/deposit",
                HttpMethod.PUT,
                new HttpEntity<>(depositReq),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BigDecimal balance = new BigDecimal(response.getBody().get("balance").toString());
        assertThat(balance).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void deposit_thenDeposit_accumulatesBalance() {
        stubActiveUser(1L);
        var created = rest.postForEntity("/accounts",
                new CreateAccountRequest(1L, AccountType.CURRENT), Map.class);
        Long accountId = ((Number) created.getBody().get("id")).longValue();

        rest.exchange("/accounts/" + accountId + "/deposit", HttpMethod.PUT,
                new HttpEntity<>(new DepositRequest(new BigDecimal("200.00"))), Map.class);
        ResponseEntity<Map> response = rest.exchange("/accounts/" + accountId + "/deposit", HttpMethod.PUT,
                new HttpEntity<>(new DepositRequest(new BigDecimal("300.00"))), Map.class);

        BigDecimal balance = new BigDecimal(response.getBody().get("balance").toString());
        assertThat(balance).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ─── WITHDRAW ──────────────────────────────────────────────────────────────

    @Test
    void withdraw_sufficientFunds_decreasesBalance() {
        stubActiveUser(1L);
        var created = rest.postForEntity("/accounts",
                new CreateAccountRequest(1L, AccountType.SAVINGS), Map.class);
        Long accountId = ((Number) created.getBody().get("id")).longValue();

        // Deposit first so there are funds to withdraw
        rest.exchange("/accounts/" + accountId + "/deposit", HttpMethod.PUT,
                new HttpEntity<>(new DepositRequest(new BigDecimal("1000.00"))), Map.class);

        var withdrawReq = new WithdrawRequest(new BigDecimal("300.00"));
        ResponseEntity<Map> response = rest.exchange(
                "/accounts/" + accountId + "/withdraw",
                HttpMethod.PUT,
                new HttpEntity<>(withdrawReq),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BigDecimal balance = new BigDecimal(response.getBody().get("balance").toString());
        assertThat(balance).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    @Test
    void withdraw_insufficientFunds_returns422() {
        stubActiveUser(1L);
        var created = rest.postForEntity("/accounts",
                new CreateAccountRequest(1L, AccountType.SAVINGS), Map.class);
        Long accountId = ((Number) created.getBody().get("id")).longValue();
        // Balance is 0 — no prior deposit

        var withdrawReq = new WithdrawRequest(new BigDecimal("100.00"));
        ResponseEntity<Map> response = rest.exchange(
                "/accounts/" + accountId + "/withdraw",
                HttpMethod.PUT,
                new HttpEntity<>(withdrawReq),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ─── BALANCE ───────────────────────────────────────────────────────────────

    @Test
    void getBalance_returnsCurrentBalance() {
        stubActiveUser(1L);
        var created = rest.postForEntity("/accounts",
                new CreateAccountRequest(1L, AccountType.SAVINGS), Map.class);
        Long accountId = ((Number) created.getBody().get("id")).longValue();

        rest.exchange("/accounts/" + accountId + "/deposit", HttpMethod.PUT,
                new HttpEntity<>(new DepositRequest(new BigDecimal("750.50"))), Map.class);

        ResponseEntity<Map> response = rest.getForEntity("/accounts/" + accountId + "/balance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BigDecimal balance = new BigDecimal(response.getBody().get("balance").toString());
        assertThat(balance).isEqualByComparingTo(new BigDecimal("750.50"));
    }

    // ─── QUERY ─────────────────────────────────────────────────────────────────

    @Test
    void getAccountsByUser_returnsAllUserAccounts() {
        stubActiveUser(1L);
        rest.postForEntity("/accounts", new CreateAccountRequest(1L, AccountType.SAVINGS), Map.class);
        rest.postForEntity("/accounts", new CreateAccountRequest(1L, AccountType.CURRENT), Map.class);

        ResponseEntity<Map> response = rest.getForEntity("/accounts/user/1", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Response is a Page — check totalElements
        assertThat(((Number) response.getBody().get("totalElements")).intValue()).isEqualTo(2);
    }
}
