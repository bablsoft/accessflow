package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for per-user data-volume budgets on a datasource (#942). All methods are
 * organization-scoped: a datasource outside {@code organizationId} raises
 * {@link DatasourceNotFoundException}; {@code applies_to} targets must belong to the organization.
 */
public interface DataBudgetAdminService {

    List<DataBudgetView> listForDatasource(UUID datasourceId, UUID organizationId);

    DataBudgetView create(UUID datasourceId, UUID organizationId, DataBudgetCommand command);

    DataBudgetView update(UUID budgetId, UUID datasourceId, UUID organizationId,
                          DataBudgetCommand command);

    void delete(UUID budgetId, UUID datasourceId, UUID organizationId);
}
