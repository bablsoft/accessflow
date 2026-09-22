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
 * Synchronous audit writes for promotion transitions (#880), the {@code DeploygovAuditWriter}
 * shape: a failed audit write is logged and swallowed so it never breaks the promotion itself.
 * {@code actorId} is null for the system rows the status projection writes.
 */
@Component
@RequiredArgsConstructor
class SchemaChangeAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(SchemaChangeAuditWriter.class);

    private final AuditLogService auditLogService;

    void record(AuditAction action, UUID promotionId, UUID organizationId, UUID actorId,
                Map<String, Object> metadata, String ipAddress, String userAgent) {
        try {
            auditLogService.record(new AuditEntry(action, AuditResourceType.SCHEMA_CHANGE_PROMOTION, promotionId,
                    organizationId, actorId, metadata, ipAddress, userAgent));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on promotion {}", action, promotionId, ex);
        }
    }
}
