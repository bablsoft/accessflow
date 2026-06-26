package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Synchronous audit writes for HTTP-driven attestation actions (certify / revoke / cancel /
 * evidence export), capturing the request IP + user-agent. The job-driven OPENED / CLOSED and
 * auto-default item actions are audited inline by the lifecycle service instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AttestationAuditWriter {

    private final AuditLogService auditLogService;

    void record(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                JwtClaims caller, Map<String, Object> metadata, RequestAuditContext auditContext) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    resourceType,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {}", action, resourceId, ex);
        }
    }
}
