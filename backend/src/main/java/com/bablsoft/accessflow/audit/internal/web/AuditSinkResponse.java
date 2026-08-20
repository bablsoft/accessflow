package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.api.AuditSinkView;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

record AuditSinkResponse(
        UUID id,
        UUID organizationId,
        String name,
        AuditSinkType type,
        Map<String, Object> config,
        boolean enabled,
        Instant cursorCreatedAt,
        Instant lastSuccessAt,
        String lastError,
        int consecutiveFailures,
        Instant nextAttemptAt,
        long behindCount,
        boolean behindCountCapped,
        Instant createdAt,
        Instant updatedAt) {

    static AuditSinkResponse from(AuditSinkView view) {
        return new AuditSinkResponse(
                view.id(),
                view.organizationId(),
                view.name(),
                view.type(),
                view.config(),
                view.enabled(),
                view.cursorCreatedAt(),
                view.lastSuccessAt(),
                view.lastError(),
                view.consecutiveFailures(),
                view.nextAttemptAt(),
                view.behindCount(),
                view.behindCountCapped(),
                view.createdAt(),
                view.updatedAt());
    }
}
