package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for per-connector response-masking policies (AF-518). All methods are
 * organization-scoped: the connector must belong to {@code organizationId}, otherwise an
 * {@link ApiConnectorNotFoundException} is thrown. {@code SCHEMA_FIELD} policies require an
 * {@code operationId}; reveal targets (group / user ids) scope who sees the unmasked value.
 */
public interface ApiConnectorMaskingAdminService {

    List<ApiConnectorMaskingPolicyView> listForConnector(UUID connectorId, UUID organizationId);

    ApiConnectorMaskingPolicyView create(UUID connectorId, UUID organizationId,
                                         CreateApiConnectorMaskingPolicyCommand command);

    ApiConnectorMaskingPolicyView update(UUID policyId, UUID connectorId, UUID organizationId,
                                         UpdateApiConnectorMaskingPolicyCommand command);

    void delete(UUID policyId, UUID connectorId, UUID organizationId);
}
