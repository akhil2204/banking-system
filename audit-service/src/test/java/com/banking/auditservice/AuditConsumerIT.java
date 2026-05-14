package com.banking.auditservice;

import com.banking.auditservice.repository.AuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Integration tests for audit-service Kafka consumers.
 *
 * Tests the full async pipeline:
 *   KafkaTemplate.send() → Kafka topic → @KafkaListener → ObjectMapper.readValue() → DB insert
 *
 * Uses a real Kafka container (Confluent cp-kafka image). The @ServiceConnection
 * annotation auto-configures spring.kafka.bootstrap-servers to the container's address.
 *
 * Awaitility polls until the consumer commits the offset and the DB row appears.
 * In tests, consumer group offset resets to "earliest" so each test replays from
 * the beginning — Kafka containers start fresh per JVM, so there is no stale state.
 *
 * The audit consumer is deliberately lenient on parsing errors (logs and moves on)
 * so bad messages don't block the partition. We test the happy path here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                // Reset offset to earliest so test messages published before the consumer
                // starts are still picked up
                "spring.kafka.consumer.auto-offset-reset=earliest"
        }
)
@Testcontainers
class AuditConsumerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    AuditRepository auditRepository;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
    }

    // ─── TRANSACTION AUDIT EVENTS ──────────────────────────────────────────────

    @Test
    void publishTransactionAuditEvent_createsAuditRecord() {
        String payload = """
                {
                  "transactionId": 1,
                  "sourceAccountId": 10,
                  "targetAccountId": 20,
                  "userId": 100,
                  "type": "TRANSFER",
                  "amount": 500.00,
                  "status": "COMPLETED",
                  "description": "Rent payment",
                  "failureReason": null,
                  "occurredAt": "2026-01-15T10:30:00"
                }""";

        kafkaTemplate.send("banking.audit.transactions", "1", payload);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var events = auditRepository.findAll();
            assertThat(events).hasSize(1);
            var event = events.get(0);
            assertThat(event.getEventType()).isEqualTo("TRANSACTION");
            assertThat(event.getEntityId()).isEqualTo(1L);
            assertThat(event.getUserId()).isEqualTo(100L);
            assertThat(event.getAction()).isEqualTo("COMPLETED");
            assertThat(event.getPayload()).contains("\"transactionId\":1");
            assertThat(event.getOccurredAt()).isNotNull();
            assertThat(event.getReceivedAt()).isNotNull();
        });
    }

    @Test
    void publishFailedTransactionEvent_actionIsFailed() {
        String payload = """
                {
                  "transactionId": 2,
                  "sourceAccountId": 10,
                  "targetAccountId": null,
                  "userId": 100,
                  "type": "DEBIT",
                  "amount": 200.00,
                  "status": "FAILED",
                  "description": "Bill payment",
                  "failureReason": "Insufficient funds",
                  "occurredAt": "2026-01-15T11:00:00"
                }""";

        kafkaTemplate.send("banking.audit.transactions", "2", payload);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var events = auditRepository.findAll();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getAction()).isEqualTo("FAILED");
            assertThat(events.get(0).getPayload()).contains("Insufficient funds");
        });
    }

    // ─── ACCOUNT AUDIT EVENTS ──────────────────────────────────────────────────

    @Test
    void publishAccountAuditEvent_createsAuditRecord() {
        String payload = """
                {
                  "accountId": 5,
                  "accountNumber": "ACC-00000005",
                  "userId": 200,
                  "action": "DEPOSITED",
                  "previousStatus": "ACTIVE",
                  "newStatus": "ACTIVE",
                  "previousBalance": 100.00,
                  "newBalance": 600.00,
                  "occurredAt": "2026-01-15T12:00:00"
                }""";

        kafkaTemplate.send("banking.audit.accounts", "5", payload);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var events = auditRepository.findAll();
            assertThat(events).hasSize(1);
            var event = events.get(0);
            assertThat(event.getEventType()).isEqualTo("ACCOUNT");
            assertThat(event.getEntityId()).isEqualTo(5L);
            assertThat(event.getUserId()).isEqualTo(200L);
            assertThat(event.getAction()).isEqualTo("DEPOSITED");
            assertThat(event.getPayload()).contains("\"accountId\":5");
        });
    }

    @Test
    void publishToTwoTopics_createsTwoAuditRecords() {
        String txnPayload = """
                {"transactionId":10,"sourceAccountId":1,"targetAccountId":null,
                 "userId":1,"type":"CREDIT","amount":100.00,"status":"COMPLETED",
                 "description":"Test","failureReason":null,"occurredAt":"2026-01-15T13:00:00"}""";

        String accPayload = """
                {"accountId":1,"accountNumber":"ACC-001","userId":1,
                 "action":"DEPOSITED","previousStatus":"ACTIVE","newStatus":"ACTIVE",
                 "previousBalance":0.00,"newBalance":100.00,"occurredAt":"2026-01-15T13:00:00"}""";

        kafkaTemplate.send("banking.audit.transactions", "10", txnPayload);
        kafkaTemplate.send("banking.audit.accounts", "1", accPayload);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var events = auditRepository.findAll();
            assertThat(events).hasSize(2);
            var types = events.stream().map(e -> e.getEventType()).toList();
            assertThat(types).containsExactlyInAnyOrder("TRANSACTION", "ACCOUNT");
        });
    }

    // ─── IMMUTABILITY VERIFICATION ─────────────────────────────────────────────

    @Test
    void auditRecord_payloadIsFullJsonSnapshot() {
        String payload = """
                {
                  "transactionId": 99,
                  "sourceAccountId": 5,
                  "targetAccountId": 6,
                  "userId": 50,
                  "type": "TRANSFER",
                  "amount": 1234.56,
                  "status": "COMPLETED",
                  "description": "Full snapshot test",
                  "failureReason": null,
                  "occurredAt": "2026-01-15T14:00:00"
                }""";

        kafkaTemplate.send("banking.audit.transactions", "99", payload);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var events = auditRepository.findAll();
            assertThat(events).hasSize(1);
            // Full original JSON stored in payload — not truncated, not transformed
            String storedPayload = events.get(0).getPayload();
            assertThat(storedPayload).contains("transactionId");
            assertThat(storedPayload).contains("1234.56");
            assertThat(storedPayload).contains("Full snapshot test");
        });
    }
}
