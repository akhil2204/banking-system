package com.banking.transactionservice.client.fallback;

import com.banking.transactionservice.client.NotificationClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/*
 * Notification fallback: do nothing.
 *
 * Notifications are already fire-and-forget — TransactionServiceImpl wraps
 * every notification call in sendNotificationSilently() which catches ALL
 * exceptions. This fallback just makes the circuit-open case explicit at the
 * Feign layer and prevents a CallNotPermittedException from bubbling up.
 *
 * The lenient thresholds in resilience4j.circuitbreaker.instances.notification-service
 * (80% failure rate, 30s open window) ensure notification-service instability
 * does not open a circuit too aggressively and generate noisy fallback log entries.
 */
@Slf4j
@Component
public class NotificationClientFallbackFactory implements FallbackFactory<NotificationClient> {

    @Override
    public NotificationClient create(Throwable cause) {
        return request -> log.warn(
                "notification-service circuit open — notification skipped (non-critical): type={} reason={}",
                request.type(), cause.getMessage());
    }
}
