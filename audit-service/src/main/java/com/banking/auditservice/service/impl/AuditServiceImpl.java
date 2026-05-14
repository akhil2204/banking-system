package com.banking.auditservice.service.impl;

import com.banking.auditservice.dto.AuditEventResponse;
import com.banking.auditservice.entity.AuditEvent;
import com.banking.auditservice.exception.AuditEventNotFoundException;
import com.banking.auditservice.repository.AuditRepository;
import com.banking.auditservice.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditRepository auditRepository;

    @Override
    @Transactional(readOnly = true)
    public AuditEventResponse getById(Long id) {
        return auditRepository.findById(id)
                .map(AuditEventResponse::from)
                .orElseThrow(() -> new AuditEventNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> getAll(String eventType, Pageable pageable) {
        if (eventType != null) {
            return auditRepository.findAllByEventType(eventType.toUpperCase(), pageable)
                    .map(AuditEventResponse::from);
        }
        return auditRepository.findAll(pageable).map(AuditEventResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> getByEntity(String eventType, Long entityId, Pageable pageable) {
        return auditRepository.findAllByEventTypeAndEntityId(eventType.toUpperCase(), entityId, pageable)
                .map(AuditEventResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> getByUser(Long userId, Pageable pageable) {
        return auditRepository.findAllByUserId(userId, pageable).map(AuditEventResponse::from);
    }

    @Override
    @Transactional
    public AuditEventResponse save(String eventType, Long entityId, Long userId,
                                   String action, String payload, LocalDateTime occurredAt) {
        AuditEvent event = AuditEvent.builder()
                .eventType(eventType)
                .entityId(entityId)
                .userId(userId)
                .action(action)
                .payload(payload)
                .occurredAt(occurredAt)
                .build();
        return AuditEventResponse.from(auditRepository.save(event));
    }
}
