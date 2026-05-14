package com.banking.auditservice.controller;

import com.banking.auditservice.dto.AuditEventResponse;
import com.banking.auditservice.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /*
     * No write endpoints — all audit records are created by Kafka consumers.
     * This enforces immutability: the only way to create an audit record is
     * by publishing a Kafka event from a source service.
     */

    @GetMapping
    public Page<AuditEventResponse> getAll(
            @RequestParam(required = false) String eventType,
            @PageableDefault(size = 20) Pageable pageable) {
        return auditService.getAll(eventType, pageable);
    }

    @GetMapping("/{id}")
    public AuditEventResponse getById(@PathVariable Long id) {
        return auditService.getById(id);
    }

    /*
     * Query all audit records for a specific entity.
     * Examples:
     *   GET /audit/entity/TRANSACTION/42  — all events for transaction #42
     *   GET /audit/entity/ACCOUNT/7       — all events for account #7
     */
    @GetMapping("/entity/{eventType}/{entityId}")
    public Page<AuditEventResponse> getByEntity(
            @PathVariable String eventType,
            @PathVariable Long entityId,
            @PageableDefault(size = 20) Pageable pageable) {
        return auditService.getByEntity(eventType, entityId, pageable);
    }

    @GetMapping("/user/{userId}")
    public Page<AuditEventResponse> getByUser(
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return auditService.getByUser(userId, pageable);
    }
}
