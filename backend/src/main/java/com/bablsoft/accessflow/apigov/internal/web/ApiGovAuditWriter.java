package com.bablsoft.accessflow.apigov.internal.web;

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

/** Synchronous audit writes for HTTP-driven API-governance actions, capturing IP + user-agent. */
@Component
@RequiredArgsConstructor
@Slf4j
class ApiGovAuditWriter {

    private final AuditLogService auditLogService;

    void record(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                JwtClaims caller, Map<String, Object> metadata, RequestAuditContext auditContext) {
        try {
            auditLogService.record(new AuditEntry(
                    action, resourceType, resourceId, caller.organizationId(), caller.userId(),
                    metadata, auditContext.ipAddress(), auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {}", action, resourceId, ex);
        }
    }
}
