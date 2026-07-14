package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantExpiryService;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultAccessGrantExpiryService implements AccessGrantExpiryService {

    private final AccessGrantRequestRepository requestRepository;
    private final AccessGrantRequestStateService stateService;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findExpiredGrantedIds(Instant now) {
        return requestRepository.findIdsByStatusAndExpiresAtBefore(AccessGrantStatus.APPROVED, now);
    }

    @Override
    public boolean expireAndRevoke(UUID accessRequestId) {
        // Delegates to the state primitive, which locks the row, revokes the materialised
        // permission, flips APPROVED → EXPIRED, and publishes the status + expiry events — all in
        // one transaction. Idempotent: returns false if the row is no longer APPROVED.
        boolean expired = stateService.expire(accessRequestId);
        if (expired) {
            recordExpiryAudit(accessRequestId);
        }
        return expired;
    }

    // System-driven audit is written here (not in the audit module's event listener) so the access
    // module does not need a reverse audit → access dependency, which would create a module cycle.
    private void recordExpiryAudit(UUID accessRequestId) {
        try {
            requestRepository.findById(accessRequestId).ifPresent(entity -> {
                var metadata = new HashMap<String, Object>();
                metadata.put("reason", "expiry");
                metadata.put("resource_kind", AccessRequestViewMapper.resourceKind(entity).name());
                if (entity.getConnectorId() != null) {
                    metadata.put("connector_id", entity.getConnectorId().toString());
                } else {
                    metadata.put("datasource_id", entity.getDatasourceId().toString());
                }
                if (entity.getGrantedPermissionId() != null) {
                    metadata.put("granted_permission_id",
                            entity.getGrantedPermissionId().toString());
                }
                auditLogService.record(new AuditEntry(
                        AuditAction.ACCESS_GRANT_EXPIRED,
                        AuditResourceType.ACCESS_GRANT_REQUEST,
                        accessRequestId,
                        entity.getOrganizationId(),
                        null,
                        metadata,
                        null,
                        null));
            });
        } catch (RuntimeException ex) {
            log.error("Audit write failed for ACCESS_GRANT_EXPIRED on access request {}",
                    accessRequestId, ex);
        }
    }
}
