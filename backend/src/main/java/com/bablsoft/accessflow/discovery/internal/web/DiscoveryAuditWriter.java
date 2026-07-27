package com.bablsoft.accessflow.discovery.internal.web;

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

/** Synchronous audit writes for HTTP-driven discovery decisions (AF-623). */
@Component
@RequiredArgsConstructor
@Slf4j
class DiscoveryAuditWriter {

    private final AuditLogService auditLogService;

    void record(AuditAction action, UUID findingId, JwtClaims caller,
                Map<String, Object> metadata, RequestAuditContext auditContext) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.DISCOVERY_FINDING,
                    findingId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on discovery finding {}", action, findingId, ex);
        }
    }
}
