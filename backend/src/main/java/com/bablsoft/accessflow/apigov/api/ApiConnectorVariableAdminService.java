package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for per-connector dynamic variables (AF-613). All methods are organization-scoped: the
 * connector must belong to {@code organizationId}, otherwise an {@link ApiConnectorNotFoundException}
 * is thrown — so a cross-org id is indistinguishable from a missing one.
 *
 * <p>Validation is authoritative at save time, not execution time. Every mutation re-materializes
 * the connector's full variable set with the candidate applied and rejects a dependency cycle, a
 * reference to an unknown variable, or a duplicate name or injection target. {@link #delete} runs
 * the inverse check and refuses to remove a variable another one still references.
 */
public interface ApiConnectorVariableAdminService {

    List<ApiConnectorVariableView> listForConnector(UUID connectorId, UUID organizationId);

    ApiConnectorVariableView create(UUID connectorId, UUID organizationId,
                                    CreateApiConnectorVariableCommand command);

    ApiConnectorVariableView update(UUID variableId, UUID connectorId, UUID organizationId,
                                    UpdateApiConnectorVariableCommand command);

    void delete(UUID variableId, UUID connectorId, UUID organizationId);

    List<ApiConnectorVariableView> reorder(UUID connectorId, UUID organizationId,
                                           ReorderApiConnectorVariablesCommand command);
}
