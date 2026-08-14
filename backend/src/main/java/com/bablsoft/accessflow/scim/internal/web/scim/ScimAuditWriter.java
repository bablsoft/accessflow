package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous audit writes for SCIM-driven mutations (#621). The actor is the IdP's provisioning
 * engine, not a user — {@code actorId} is null and the token's identity travels in the metadata.
 * Failures are swallowed: audit problems must never surface as SCIM errors to the IdP.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ScimAuditWriter {

    private final AuditLogService auditLogService;

    void record(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                ScimPrincipal principal, Map<String, Object> metadata,
                RequestAuditContext auditContext) {
        try {
            var enriched = new HashMap<String, Object>(metadata);
            enriched.put("scim_token_id", principal.tokenId().toString());
            enriched.put("scim_token_name", principal.tokenName());
            auditLogService.record(new AuditEntry(
                    action,
                    resourceType,
                    resourceId,
                    principal.organizationId(),
                    null,
                    enriched,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {}", action, resourceId, ex);
        }
    }
}
