package com.banking.auditservice.dto;

import com.banking.auditservice.entity.AuditEvent;

import java.time.LocalDateTime;

public record AuditEventResponse(
        Long id,
        String eventType,
        Long entityId,
        Long userId,
        String action,
        String payload,
        LocalDateTime occurredAt,
        LocalDateTime receivedAt
) {
    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(), e.getEventType(), e.getEntityId(), e.getUserId(),
                e.getAction(), e.getPayload(), e.getOccurredAt(), e.getReceivedAt()
        );
    }
}
