package com.banking.transactionservice.client;

import com.banking.transactionservice.client.fallback.NotificationClientFallbackFactory;
import com.banking.transactionservice.dto.NotificationRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "notification-service", fallbackFactory = NotificationClientFallbackFactory.class)
public interface NotificationClient {

    /*
     * Notifications are best-effort — if this call fails, the transaction
     * outcome is NOT affected. TransactionServiceImpl wraps every call
     * to this client in sendNotificationSilently(), which catches and
     * logs all exceptions without rethrowing.
     */
    @PostMapping("/notifications")
    void send(@RequestBody NotificationRequest request);
}
