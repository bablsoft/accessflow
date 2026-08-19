package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for per-datasource result-export policies (#626). All methods are
 * organization-scoped: the datasource must belong to {@code organizationId}, otherwise a
 * {@link DatasourceNotFoundException} is thrown. {@code applies_to} group / user targets must
 * belong to the same organization.
 */
public interface ExportPolicyAdminService {

    List<ExportPolicyView> listForDatasource(UUID datasourceId, UUID organizationId);

    ExportPolicyView create(UUID datasourceId, UUID organizationId,
                            CreateExportPolicyCommand command);

    ExportPolicyView update(UUID policyId, UUID datasourceId, UUID organizationId,
                            UpdateExportPolicyCommand command);

    void delete(UUID policyId, UUID datasourceId, UUID organizationId);
}
