package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantAlreadyExistsException;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.CreatePermissionCommand;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns a final-stage-approved access request into a time-boxed {@code datasource_user_permissions}
 * row. Runs inside the approving transaction so approval + grant commit atomically.
 *
 * <p>Pre-existing-permission policy: a JIT grant never silently deletes a standing/admin permission
 * (one with no {@code expires_at}) — that raises {@link AccessGrantAlreadyExistsException}. When the
 * requester already holds another time-boxed (JIT) permission, that row is revoked and replaced so
 * the new grant's capabilities/expiry take effect (extend/widen).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AccessGrantMaterializer {

    private final AccessGrantRequestRepository requestRepository;
    private final AccessGrantRequestStateService stateService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final DatasourceAdminService datasourceAdminService;

    void materialize(UUID accessRequestId, UUID approvedByUserId) {
        var entity = requestRepository.findById(accessRequestId)
                .orElseThrow(() -> new AccessRequestNotFoundException(accessRequestId));
        var expiresAt = Instant.now().plus(Duration.parse(entity.getRequestedDuration()));
        replaceExistingTimeBoxedPermission(entity);
        var command = new CreatePermissionCommand(
                entity.getRequesterId(),
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                false,
                null,
                toList(entity.getAllowedSchemas()),
                toList(entity.getAllowedTables()),
                null,
                expiresAt);
        var granted = datasourceAdminService.grantPermission(entity.getDatasourceId(),
                entity.getOrganizationId(), approvedByUserId, command);
        stateService.attachGrant(accessRequestId, granted.id(), expiresAt);
    }

    private void replaceExistingTimeBoxedPermission(AccessGrantRequestEntity entity) {
        // JIT access manages the per-user datasource_user_permissions row specifically, so it must
        // look at the direct grant only — never a group grant (whose id it could not revoke here).
        var existing = permissionLookupService.findDirectFor(entity.getRequesterId(),
                entity.getDatasourceId());
        if (existing.isEmpty()) {
            return;
        }
        DatasourceUserPermissionView permission = existing.get();
        if (permission.expiresAt() == null) {
            throw new AccessGrantAlreadyExistsException(entity.getRequesterId(),
                    entity.getDatasourceId());
        }
        log.info("Replacing existing time-boxed permission {} for user {} on datasource {} "
                        + "to materialise access request {}",
                permission.id(), entity.getRequesterId(), entity.getDatasourceId(), entity.getId());
        datasourceAdminService.revokePermission(entity.getDatasourceId(),
                entity.getOrganizationId(), permission.id());
    }

    private static List<String> toList(String[] values) {
        return values == null ? null : List.of(values);
    }
}
