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
class DeploygovAuditWriter {

    private final AuditLogService auditLogService;

    void record(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                UUID organizationId, UUID userId, Map<String, Object> metadata, String ipAddress) {
        try {
            auditLogService.record(new AuditEntry(action, resourceType, resourceId, organizationId,
                    userId, metadata, ipAddress, null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {}", action, resourceId, ex);
        }
    }
}
