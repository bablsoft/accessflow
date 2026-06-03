package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for per-table row-security policies on a datasource. All methods are
 * organization-scoped: the datasource must belong to {@code organizationId}, otherwise a
 * {@link DatasourceNotFoundException} is thrown. {@code applies_to} group / user targets must
 * belong to the same organization.
 */
public interface RowSecurityPolicyAdminService {

    List<RowSecurityPolicyView> listForDatasource(UUID datasourceId, UUID organizationId);

    RowSecurityPolicyView create(UUID datasourceId, UUID organizationId,
                                 CreateRowSecurityPolicyCommand command);

    RowSecurityPolicyView update(UUID policyId, UUID datasourceId, UUID organizationId,
                                 UpdateRowSecurityPolicyCommand command);

    void delete(UUID policyId, UUID datasourceId, UUID organizationId);
}
