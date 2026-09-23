package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for per-table row-limit policies on a datasource (#934). All methods are
 * organization-scoped: the datasource must belong to {@code organizationId}, otherwise a
 * {@link DatasourceNotFoundException} is thrown. {@code applies_to} group / user targets must
 * belong to the same organization.
 */
public interface RowLimitPolicyAdminService {

    List<RowLimitPolicyView> listForDatasource(UUID datasourceId, UUID organizationId);

    RowLimitPolicyView create(UUID datasourceId, UUID organizationId,
                              CreateRowLimitPolicyCommand command);

    RowLimitPolicyView update(UUID policyId, UUID datasourceId, UUID organizationId,
                              UpdateRowLimitPolicyCommand command);

    void delete(UUID policyId, UUID datasourceId, UUID organizationId);
}
