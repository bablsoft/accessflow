package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public interface DatasourceAdminService {

    PageResponse<DatasourceView> listForAdmin(UUID organizationId, PageRequest pageRequest);

    PageResponse<DatasourceView> listForUser(UUID organizationId, UUID userId, PageRequest pageRequest);

    DatasourceView getForAdmin(UUID id, UUID organizationId);

    DatasourceView getForUser(UUID id, UUID organizationId, UUID userId);

    DatasourceView create(CreateDatasourceCommand command);

    DatasourceView update(UUID id, UUID organizationId, UpdateDatasourceCommand command);

    void deactivate(UUID id, UUID organizationId);

    ConnectionTestResult test(UUID id, UUID organizationId);

    /**
     * Tests connectivity to a candidate read-replica using the supplied {@code command} values. The
     * datasource id is used to resolve the same JDBC driver as the primary (replicas share the
     * driver class). When {@code command.password()} is {@code null}, the persisted replica
     * password is used; if neither is available, a {@link DatasourceConnectionTestException} is
     * thrown.
     */
    ConnectionTestResult testReplica(UUID id, UUID organizationId, TestReplicaCommand command);

    DatabaseSchemaView introspectSchema(UUID id, UUID organizationId, UUID userId, boolean isAdmin);

    DatabaseSchemaView introspectSchemaForSystem(UUID id, UUID organizationId);

    List<DatasourcePermissionView> listPermissions(UUID datasourceId, UUID organizationId);

    DatasourcePermissionView grantPermission(UUID datasourceId, UUID organizationId,
                                             UUID grantedByUserId, CreatePermissionCommand command);

    void revokePermission(UUID datasourceId, UUID organizationId, UUID permissionId);
}
