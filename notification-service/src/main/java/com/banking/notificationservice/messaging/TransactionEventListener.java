package com.banking.notificationservice.messaging;

import com.banking.notificationservice.config.RabbitMQConfig;
import com.banking.notificationservice.dto.SendNotificationRequest;
import com.banking.notificationservice.entity.NotificationChannel;
import com.banking.notificationservice.entity.NotificationType;
import com.banking.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventListener {

    private final NotificationService notificationService;

    /**
     * Consume transaction events from RabbitMQ and persist notifications.
     *
     * Contrast with the old Feign approach:
     * - Old: transaction-service called /notifications synchronously; slow
     *   notification-service added latency to the transaction response.
     * - New: event is consumed here independently; transaction-service is
     *   completely decoupled from our availability and throughput.
     *
     * ACK / NACK behaviour (configured in RabbitMQConfig):
     * - Success  → Spring ACKs the message; it is removed from the queue.
     * - Exception → Spring NACKs without requeue (defaultRequeueRejected=false)
     *               → message is routed to DLQ for inspection.
     *
     * We intentionally rethrow exceptions here (unlike the old silent catch)
     * because the broker now provides the retry/DLQ safety net.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void onTransactionEvent(TransactionEvent event) {
        log.info("Received transaction event: txn={} userId={} type={}",
                event.transactionId(), event.userId(), event.type());
        try {
            notificationService.send(new SendNotificationRequest(
                    event.userId(),
                    NotificationType.valueOf(event.type()),
                    NotificationChannel.valueOf(event.channel()),
                    event.title(),
                    event.message()
            ));
            log.info("Notification persisted: txn={} userId={}", event.transactionId(), event.userId());
        } catch (IllegalArgumentException e) {
            // Unknown type/channel name — this is a bad message, not a transient failure.
            // Retrying will never fix it, so we let it go to DLQ immediately.
            log.error("Invalid event payload, routing to DLQ: txn={} type={} channel={}",
                    event.transactionId(), event.type(), event.channel());
            throw e;
        } catch (Exception e) {
            // Transient failure (DB down, etc.) — also routes to DLQ.
            // Future: configure retry with back-off before DLQ for transient errors.
            log.error("Failed to process notification event, routing to DLQ: txn={} error={}",
                    event.transactionId(), e.getMessage());
            throw e;
        }
    }
}
