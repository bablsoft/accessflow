package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Admin-facing management of API connectors and the per-user permissions that share governed
 * connectivity with the team. All operations are organization-scoped; mutating operations are
 * admin-gated at the controller. Auth secrets are AES-256-GCM encrypted and never returned.
 */
public interface ApiConnectorAdminService {

    PageResponse<ApiConnectorView> listForAdmin(UUID organizationId, PageRequest pageRequest);

    /** Connectors the user is granted access to (active permission, not expired). */
    PageResponse<ApiConnectorView> listForUser(UUID organizationId, UUID userId, PageRequest pageRequest);

    ApiConnectorView getForAdmin(UUID id, UUID organizationId);

    ApiConnectorView getForUser(UUID id, UUID organizationId, UUID userId);

    ApiConnectorView create(CreateApiConnectorCommand command);

    ApiConnectorView update(UUID id, UUID organizationId, UpdateApiConnectorCommand command);

    void delete(UUID id, UUID organizationId);

    ApiConnectionTestResult test(UUID id, UUID organizationId);

    List<ApiConnectorPermissionView> listPermissions(UUID connectorId, UUID organizationId);

    ApiConnectorPermissionView grantPermission(UUID connectorId, UUID organizationId,
                                               UUID grantedByUserId,
                                               GrantApiConnectorPermissionCommand command);

    ApiConnectorPermissionView updatePermission(UUID connectorId, UUID organizationId,
                                                UUID permissionId,
                                                UpdateApiConnectorPermissionCommand command);

    void revokePermission(UUID connectorId, UUID organizationId, UUID permissionId);
}
