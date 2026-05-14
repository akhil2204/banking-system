package com.banking.transactionservice;

import com.banking.transactionservice.dto.DebitCreditRequest;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
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
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for transaction-service.
 *
 * WireMock stubs account-service (Feign dependency).
 * RabbitMQ container is used — transaction-service publishes events after every
 * terminal state, so the AMQP connection must succeed to avoid publish errors.
 * Kafka bootstrap server is set to a non-existent address: the AuditEventPublisher
 * catches Kafka failures silently (fire-and-forget).
 *
 * Saga compensation is tested by making WireMock return 404 on the deposit call
 * after a successful withdraw — simulating destination account not found.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.openfeign.circuitbreaker.enabled=false",
                // Kafka publishes fail silently
                "spring.kafka.bootstrap-servers=localhost:29092"
        }
)
@Testcontainers
class TransactionControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

    static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("feign.client.config.account-service.url", wireMock::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        transactionRepository.deleteAll();
    }

    private static final String ACTIVE_ACCOUNT_1 = """
            {"id":1,"userId":10,"accountNumber":"ACC-001","status":"ACTIVE"}""";
    private static final String ACTIVE_ACCOUNT_2 = """
            {"id":2,"userId":20,"accountNumber":"ACC-002","status":"ACTIVE"}""";

    private void stubAccount(Long id, String body) {
        wireMock.stubFor(get(urlEqualTo("/accounts/" + id))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubDeposit(Long accountId, String responseBody) {
        wireMock.stubFor(put(urlEqualTo("/accounts/" + accountId + "/deposit"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    private void stubWithdraw(Long accountId, String responseBody) {
        wireMock.stubFor(put(urlEqualTo("/accounts/" + accountId + "/withdraw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    private void stubWithdraw404(Long accountId) {
        wireMock.stubFor(put(urlEqualTo("/accounts/" + accountId + "/withdraw"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":404,\"error\":\"Not Found\"}")));
    }

    // ─── CREDIT (money IN) ─────────────────────────────────────────────────────

    @Test
    void credit_validAccount_returns201Completed() {
        stubAccount(1L, ACTIVE_ACCOUNT_1);
        stubDeposit(1L, ACTIVE_ACCOUNT_1);

        var request = new DebitCreditRequest(1L, new BigDecimal("500.00"), "Test credit");
        ResponseEntity<Map> response = rest.postForEntity("/transactions/credit", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("type", "CREDIT");
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
        assertThat(response.getBody()).containsEntry("targetAccountId", 1);
    }

    @Test
    void credit_accountNotFound_returns201Failed() {
        wireMock.stubFor(get(urlEqualTo("/accounts/999"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":404,\"error\":\"Not Found\"}")));

        var request = new DebitCreditRequest(999L, new BigDecimal("100.00"), "Bad account");
        ResponseEntity<Map> response = rest.postForEntity("/transactions/credit", request, Map.class);

        // 201 always — check status field
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "FAILED");
    }

    // ─── DEBIT (money OUT) ─────────────────────────────────────────────────────

    @Test
    void debit_validAccount_returns201Completed() {
        stubAccount(1L, ACTIVE_ACCOUNT_1);
        stubWithdraw(1L, ACTIVE_ACCOUNT_1);

        var request = new DebitCreditRequest(1L, new BigDecimal("200.00"), "Test debit");
        ResponseEntity<Map> response = rest.postForEntity("/transactions/debit", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("type", "DEBIT");
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
        assertThat(response.getBody()).containsEntry("sourceAccountId", 1);
    }

    // ─── TRANSFER (Saga pattern) ────────────────────────────────────────────────

    @Test
    void transfer_validAccounts_returns201Completed() {
        stubAccount(1L, ACTIVE_ACCOUNT_1);
        stubAccount(2L, ACTIVE_ACCOUNT_2);
        stubWithdraw(1L, ACTIVE_ACCOUNT_1);
        stubDeposit(2L, ACTIVE_ACCOUNT_2);

        var request = new TransferRequest(1L, 2L, new BigDecimal("300.00"), "Rent payment");
        ResponseEntity<Map> response = rest.postForEntity("/transactions/transfer", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("type", "TRANSFER");
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
        assertThat(response.getBody()).containsEntry("sourceAccountId", 1);
        assertThat(response.getBody()).containsEntry("targetAccountId", 2);
    }

    @Test
    void transfer_destinationNotFound_sagaCompensates_returns201Failed() {
        // Source account exists; destination does not
        stubAccount(1L, ACTIVE_ACCOUNT_1);
        wireMock.stubFor(get(urlEqualTo("/accounts/999"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":404}")));
        // Compensation: deposit back to source must succeed
        stubDeposit(1L, ACTIVE_ACCOUNT_1);

        var request = new TransferRequest(1L, 999L, new BigDecimal("100.00"), "Bad transfer");
        ResponseEntity<Map> response = rest.postForEntity("/transactions/transfer", request, Map.class);

        // Transfer processed (201) but failed in business logic
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "FAILED");
        assertThat(response.getBody().get("failureReason")).isNotNull();

        // Verify compensation ran: deposit back to source was called
        wireMock.verify(putRequestedFor(urlEqualTo("/accounts/1/deposit")));
    }

    @Test
    void transfer_selfTransfer_returns422() {
        var request = new TransferRequest(1L, 1L, new BigDecimal("100.00"), "Same account");
        ResponseEntity<Map> response = rest.postForEntity("/transactions/transfer", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ─── QUERY ─────────────────────────────────────────────────────────────────

    @Test
    void getTransactionById_existingTransaction_returns200() {
        stubAccount(1L, ACTIVE_ACCOUNT_1);
        stubDeposit(1L, ACTIVE_ACCOUNT_1);

        var created = rest.postForEntity("/transactions/credit",
                new DebitCreditRequest(1L, new BigDecimal("100.00"), "Test"), Map.class);
        Long id = ((Number) created.getBody().get("id")).longValue();

        ResponseEntity<Map> response = rest.getForEntity("/transactions/" + id, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("id", id.intValue());
    }

    @Test
    void listTransactions_filterByStatus_returnsOnlyMatching() {
        stubAccount(1L, ACTIVE_ACCOUNT_1);
        stubDeposit(1L, ACTIVE_ACCOUNT_1);
        rest.postForEntity("/transactions/credit",
                new DebitCreditRequest(1L, new BigDecimal("100.00"), "First"), Map.class);

        ResponseEntity<Map> response = rest.getForEntity(
                "/transactions?status=COMPLETED&page=0&size=10", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("totalElements")).intValue()).isGreaterThan(0);
    }

    @Test
    void getTransactionsByAccount_returnsAllForAccount() {
        stubAccount(1L, ACTIVE_ACCOUNT_1);
        stubDeposit(1L, ACTIVE_ACCOUNT_1);
        stubWithdraw(1L, ACTIVE_ACCOUNT_1);

        rest.postForEntity("/transactions/credit",
                new DebitCreditRequest(1L, new BigDecimal("500.00"), "Credit"), Map.class);
        rest.postForEntity("/transactions/debit",
                new DebitCreditRequest(1L, new BigDecimal("100.00"), "Debit"), Map.class);

        ResponseEntity<Map> response = rest.getForEntity("/transactions/account/1", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("totalElements")).intValue()).isEqualTo(2);
    }
}
