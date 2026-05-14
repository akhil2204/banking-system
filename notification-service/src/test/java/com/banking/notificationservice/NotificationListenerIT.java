package com.banking.notificationservice;

import com.banking.notificationservice.entity.NotificationStatus;
import com.banking.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Integration tests for notification-service RabbitMQ consumer.
 *
 * Tests the full async pipeline:
 *   RabbitTemplate.convertAndSend() → RabbitMQ exchange → queue → @RabbitListener → DB insert
 *
 * Awaitility is used to wait for async processing. The consumer runs on a
 * separate thread pool managed by SimpleRabbitListenerContainerFactory.
 *
 * TransactionEvent JSON matches the exact field names notification-service's
 * Jackson converter expects:
 *   transactionId, userId, type, channel, title, message
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false"
        }
)
@Testcontainers
class NotificationListenerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    @Test
    void publishTransactionCompleted_createsUnreadNotification() {
        // Publish a TransactionEvent JSON to the exchange with routing key transaction.completed
        // The listener will deserialize the JSON into TransactionEvent record and call
        // notificationService.send() which persists a Notification entity.
        String event = """
                {
                  "transactionId": 42,
                  "userId": 10,
                  "type": "TRANSACTION_CREDIT",
                  "channel": "IN_APP",
                  "title": "Money Received",
                  "message": "You received $500.00"
                }""";

        rabbitTemplate.convertAndSend(
                "banking.transactions",
                "transaction.completed",
                event
        );

        // Wait up to 5 seconds for the async consumer to persist the notification
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);
            var notification = notifications.get(0);
            assertThat(notification.getUserId()).isEqualTo(10L);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.UNREAD);
        });
    }

    @Test
    void publishTransactionFailed_createsNotificationForFailedEvent() {
        String event = """
                {
                  "transactionId": 99,
                  "userId": 20,
                  "type": "TRANSACTION_DEBIT",
                  "channel": "IN_APP",
                  "title": "Transaction Failed",
                  "message": "Your debit of $200.00 failed"
                }""";

        rabbitTemplate.convertAndSend(
                "banking.transactions",
                "transaction.failed",
                event
        );

        await().atMost(5, SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).getUserId()).isEqualTo(20L);
        });
    }

    @Test
    void publishTwoEvents_createsTwoNotifications() {
        String event1 = """
                {"transactionId":1,"userId":1,"type":"TRANSACTION_CREDIT",
                 "channel":"IN_APP","title":"T1","message":"Msg1"}""";
        String event2 = """
                {"transactionId":2,"userId":2,"type":"TRANSACTION_DEBIT",
                 "channel":"IN_APP","title":"T2","message":"Msg2"}""";

        rabbitTemplate.convertAndSend("banking.transactions", "transaction.completed", event1);
        rabbitTemplate.convertAndSend("banking.transactions", "transaction.completed", event2);

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertThat(notificationRepository.count()).isEqualTo(2)
        );
    }

    @Test
    void publishInvalidType_routesToDlqAndDoesNotCreateNotification() {
        // "INVALID_TYPE" is not a valid NotificationType enum value.
        // The listener throws IllegalArgumentException → NACK → goes to DLQ.
        // No notification row created.
        String badEvent = """
                {"transactionId":5,"userId":5,"type":"INVALID_TYPE",
                 "channel":"IN_APP","title":"Bad","message":"Bad"}""";

        rabbitTemplate.convertAndSend("banking.transactions", "transaction.completed", badEvent);

        // Wait briefly, then assert no notification created (bad message went to DLQ)
        await().during(2, SECONDS).atMost(3, SECONDS).untilAsserted(() ->
                assertThat(notificationRepository.count()).isEqualTo(0)
        );
    }
}
