package com.partqam.accessflow.core.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface DatasourceAdminService {

    Page<DatasourceView> listForAdmin(UUID organizationId, Pageable pageable);

    Page<DatasourceView> listForUser(UUID organizationId, UUID userId, Pageable pageable);

    DatasourceView getForAdmin(UUID id, UUID organizationId);

    DatasourceView getForUser(UUID id, UUID organizationId, UUID userId);

    DatasourceView create(CreateDatasourceCommand command);

    DatasourceView update(UUID id, UUID organizationId, UpdateDatasourceCommand command);

    void deactivate(UUID id, UUID organizationId);

    ConnectionTestResult test(UUID id, UUID organizationId);

    DatabaseSchemaView introspectSchema(UUID id, UUID organizationId, UUID userId, boolean isAdmin);

    DatabaseSchemaView introspectSchemaForSystem(UUID id, UUID organizationId);

    List<DatasourcePermissionView> listPermissions(UUID datasourceId, UUID organizationId);

    DatasourcePermissionView grantPermission(UUID datasourceId, UUID organizationId,
                                             UUID grantedByUserId, CreatePermissionCommand command);

    void revokePermission(UUID datasourceId, UUID organizationId, UUID permissionId);
}
