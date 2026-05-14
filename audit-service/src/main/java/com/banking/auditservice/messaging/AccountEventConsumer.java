package com.banking.auditservice.messaging;

import com.banking.auditservice.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.AUDIT_ACCOUNTS, groupId = "audit-service")
    public void consume(String payload) {
        try {
            AccountAuditEvent event = objectMapper.readValue(payload, AccountAuditEvent.class);
            log.info("Received account audit event: account={} action={}",
                    event.accountId(), event.action());

            auditService.save(
                    "ACCOUNT",
                    event.accountId(),
                    event.userId(),
                    event.action(),
                    payload,
                    event.occurredAt()
            );
        } catch (Exception e) {
            log.error("Failed to process account audit event: payload={} error={}", payload, e.getMessage());
        }
    }
}
