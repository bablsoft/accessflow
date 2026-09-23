package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Synchronous audit writes for this module (#880, #881), the {@code DeploygovAuditWriter} shape: a
 * failed audit write is logged and swallowed so it never breaks the operation itself.
 * {@code actorId} is null for the system rows the status projection and the drift job write.
 */
@Component
@RequiredArgsConstructor
class SchemaChangeAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(SchemaChangeAuditWriter.class);

    private final AuditLogService auditLogService;

    /** Promotion rows (#880) — the original, narrower signature every promotion call site uses. */
    void record(AuditAction action, UUID promotionId, UUID organizationId, UUID actorId,
                Map<String, Object> metadata, String ipAddress, String userAgent) {
        record(action, AuditResourceType.SCHEMA_CHANGE_PROMOTION, promotionId, organizationId, actorId,
                metadata, ipAddress, userAgent);
    }

    /** Any resource this module owns — drift scans and findings joined promotions in #881. */
    void record(AuditAction action, AuditResourceType resourceType, UUID resourceId, UUID organizationId,
                UUID actorId, Map<String, Object> metadata, String ipAddress, String userAgent) {
        try {
            auditLogService.record(new AuditEntry(action, resourceType, resourceId,
                    organizationId, actorId, metadata, ipAddress, userAgent));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {} {}", action, resourceType, resourceId, ex);
        }
    }
}
