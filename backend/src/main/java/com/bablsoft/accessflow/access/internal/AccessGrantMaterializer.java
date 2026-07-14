package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantAlreadyExistsException;
import com.bablsoft.accessflow.access.api.AccessRequestNotFoundException;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import com.bablsoft.accessflow.apigov.api.GrantApiConnectorPermissionCommand;
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
 * Turns a final-stage-approved access request into a time-boxed permission row —
 * {@code datasource_user_permissions} for datasource requests, {@code api_connector_user_permissions}
 * for API-connector requests. Runs inside the approving transaction so approval + grant commit
 * atomically.
 *
 * <p>Pre-existing-permission policy (both kinds): a JIT grant never silently deletes a standing/admin
 * permission (one with no {@code expires_at}) — that raises {@link AccessGrantAlreadyExistsException}.
 * When the requester already holds another time-boxed (JIT) permission, that row is revoked/replaced
 * so the new grant's capabilities/expiry take effect (extend/widen). Only the <em>direct</em> row is
 * considered — group grants are never touched.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AccessGrantMaterializer {

    private final AccessGrantRequestRepository requestRepository;
    private final AccessGrantRequestStateService stateService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final ApiConnectorPermissionLookupService connectorPermissionLookupService;
    private final ApiConnectorAdminService apiConnectorAdminService;

    void materialize(UUID accessRequestId, UUID approvedByUserId) {
        var entity = requestRepository.findById(accessRequestId)
                .orElseThrow(() -> new AccessRequestNotFoundException(accessRequestId));
        var expiresAt = Instant.now().plus(Duration.parse(entity.getRequestedDuration()));
        if (entity.isConnectorRequest()) {
            materializeConnectorGrant(entity, approvedByUserId, expiresAt);
            return;
        }
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

    private void materializeConnectorGrant(AccessGrantRequestEntity entity, UUID approvedByUserId,
                                           Instant expiresAt) {
        requireNoStandingConnectorPermission(entity);
        // grantPermission upserts on (connector_id, user_id), so an existing time-boxed row is
        // replaced in place. Break-glass and response-field restrictions are never JIT-grantable.
        var command = new GrantApiConnectorPermissionCommand(
                entity.getRequesterId(),
                entity.isCanRead(),
                entity.isCanWrite(),
                false,
                expiresAt,
                toList(entity.getAllowedOperations()),
                null);
        var granted = apiConnectorAdminService.grantPermission(entity.getConnectorId(),
                entity.getOrganizationId(), approvedByUserId, command);
        stateService.attachGrant(entity.getId(), granted.id(), expiresAt);
    }

    private void requireNoStandingConnectorPermission(AccessGrantRequestEntity entity) {
        // Direct grant only — a standing group grant must never block (or be clobbered by) a JIT
        // materialisation; the upsert touches the user's own row exclusively.
        connectorPermissionLookupService
                .findDirectFor(entity.getConnectorId(), entity.getRequesterId())
                .ifPresent(permission -> {
                    if (permission.expiresAt() == null) {
                        throw AccessGrantAlreadyExistsException.forConnector(
                                entity.getRequesterId(), entity.getConnectorId());
                    }
                    log.info("Replacing existing time-boxed permission {} for user {} on API "
                                    + "connector {} to materialise access request {}",
                            permission.id(), entity.getRequesterId(), entity.getConnectorId(),
                            entity.getId());
                });
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
