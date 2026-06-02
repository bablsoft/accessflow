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

    record SubmitCommand(
            UUID organizationId,
            UUID requesterId,
            UUID datasourceId,
            boolean canRead,
            boolean canWrite,
            boolean canDdl,
            List<String> allowedSchemas,
            List<String> allowedTables,
            String requestedDuration,
            String justification) {
    }

    record DatasourceOption(UUID id, String name) {
    }
}
