package com.bablsoft.accessflow.access.internal.web;

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
 * Synchronous audit writes for HTTP-driven access-request actions (submit / approve / reject /
 * cancel / revoke), capturing the request IP + user-agent. The job-driven EXPIRED action is
 * audited asynchronously by the audit module's event listener instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AccessRequestAuditWriter {

    private final AuditLogService auditLogService;

    void record(AuditAction action, UUID accessRequestId, JwtClaims caller,
                Map<String, Object> metadata, RequestAuditContext auditContext) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.ACCESS_GRANT_REQUEST,
                    accessRequestId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on access request {}", action, accessRequestId, ex);
        }
    }
}
