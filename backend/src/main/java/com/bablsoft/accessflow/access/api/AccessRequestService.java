package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.UUID;

/**
 * Requester-facing operations: submit a new access-grant request, list one's own requests, cancel
 * a pending request, and enumerate the datasources a user may request access to.
 */
public interface AccessRequestService {

    AccessRequestView submit(SubmitCommand command);

    /**
     * Lists the caller's own access requests, newest first. {@code statusFilter} is optional
     * (null = all statuses).
     */
    PageResponse<AccessRequestView> listMine(UUID organizationId, UUID requesterId,
                                             AccessGrantStatus statusFilter, PageRequest pageRequest);

    /**
     * Cancels a {@code PENDING} request owned by {@code requesterId}. Throws
     * {@link AccessRequestNotFoundException} when missing/foreign and
     * {@link AccessRequestNotCancellableException} when not pending.
     */
    void cancel(UUID accessRequestId, UUID requesterId, UUID organizationId);

    /**
     * Datasources in the organization that a user can target with an access request (id + name only;
     * no connection details). Unlike the datasource list, this is not scoped to existing permissions —
     * JIT access exists precisely to grant access a user does not yet have.
     */
    List<DatasourceOption> listRequestableDatasources(UUID organizationId);

    /**
     * Introspects the live schema (schema + table names only) of a requestable datasource so a JIT
     * requester can scope their access request. Org-scoped but NOT permission-gated — like
     * {@link #listRequestableDatasources(UUID)}, JIT access exists to scope access a user does not yet
     * have. Throws {@link com.bablsoft.accessflow.core.api.DatasourceNotFoundException} when the
     * datasource is not in the organization and
     * {@link com.bablsoft.accessflow.core.api.DatasourceConnectionTestException} when introspection
     * fails.
     */
    DatabaseSchemaView introspectRequestableDatasourceSchema(UUID datasourceId, UUID organizationId);

    /**
     * API connectors in the organization that a user can target with an access request (id, name,
     * protocol only). Like {@link #listRequestableDatasources(UUID)}, not scoped to existing
     * permissions — JIT access exists precisely to grant access a user does not yet have.
     */
    List<ConnectorOption> listRequestableConnectors(UUID organizationId);

    /**
     * The operation catalog of a requestable connector so a JIT requester can scope their access
     * request to specific operations. Org-scoped but NOT permission-gated. Throws
     * {@link com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException} when the connector
     * is not an active connector in the organization.
     */
    List<ConnectorOperationOption> listRequestableConnectorOperations(UUID connectorId,
                                                                      UUID organizationId);

    /**
     * Exactly one of {@code datasourceId} / {@code connectorId} is set. {@code allowedOperations}
     * and {@code allowedSchemas}/{@code allowedTables}/{@code canDdl}/{@code preApproveQueries}
     * only apply to the connector / datasource kind respectively.
     */
    record SubmitCommand(
            UUID organizationId,
            UUID requesterId,
            UUID datasourceId,
            UUID connectorId,
            boolean canRead,
            boolean canWrite,
            boolean canDdl,
            List<String> allowedSchemas,
            List<String> allowedTables,
            List<String> allowedOperations,
            String requestedDuration,
            String justification,
            boolean preApproveQueries) {
    }

    record DatasourceOption(UUID id, String name) {
    }

    record ConnectorOption(UUID id, String name, String protocol) {
    }

    record ConnectorOperationOption(String operationId, String verb, String path, String summary,
                                    boolean write) {
    }
}
