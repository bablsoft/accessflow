package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for per-column masking policies on a datasource. All methods are organization-scoped:
 * the datasource must belong to {@code organizationId}, otherwise a {@link DatasourceNotFoundException}
 * is thrown. Reveal targets (group / user ids) must belong to the same organization.
 */
public interface MaskingPolicyAdminService {

    List<MaskingPolicyView> listForDatasource(UUID datasourceId, UUID organizationId);

    MaskingPolicyView create(UUID datasourceId, UUID organizationId,
                             CreateMaskingPolicyCommand command);

    MaskingPolicyView update(UUID policyId, UUID datasourceId, UUID organizationId,
                             UpdateMaskingPolicyCommand command);

    void delete(UUID policyId, UUID datasourceId, UUID organizationId);
}
