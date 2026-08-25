package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Synchronous audit writes for deployment-governance actions (#692). Called from the service layer
 * (the trigger authenticates machines, so there is no browser user-agent to capture); a failed
 * audit write is logged and swallowed so it never breaks the governed operation itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeploygovAuditWriter {

    private final AuditLogService auditLogService;

    public void record(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                       UUID organizationId, UUID userId, Map<String, Object> metadata,
                       String ipAddress) {
        record(action, resourceType, resourceId, organizationId, userId, metadata, ipAddress, null);
    }

    /** #695: overload for HTTP-driven writes (reviewer decisions, cancel) capturing the user-agent. */
    public void record(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                       UUID organizationId, UUID userId, Map<String, Object> metadata,
                       String ipAddress, String userAgent) {
        try {
            auditLogService.record(new AuditEntry(action, resourceType, resourceId, organizationId,
                    userId, metadata, ipAddress, userAgent));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {}", action, resourceId, ex);
        }
    }
}
