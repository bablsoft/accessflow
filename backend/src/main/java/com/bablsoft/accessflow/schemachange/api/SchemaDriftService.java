package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Read side of schema drift plus the two admin actions (#878, epic #870; implemented in #881).
 * Scans are otherwise scheduled; drift only ever records findings and never writes to the
 * customer database. Every method is organization-scoped, 404-never-403.
 */
public interface SchemaDriftService {

    PageResponse<SchemaDriftScanView> listScans(UUID organizationId, SchemaDriftScanListFilter filter,
                                                PageRequest pageRequest);

    PageResponse<SchemaDriftFindingView> listFindings(UUID organizationId, SchemaDriftFindingListFilter filter,
                                                      PageRequest pageRequest);

    /**
     * Runs one scan of one environment now.
     *
     * <p>Returns as soon as the scan is accepted, carrying the id to poll — introspection opens a
     * connection to a customer database and cannot sit on a request thread.
     *
     * @throws SchemaChangeEnvironmentNotFoundException when the environment is not in this organization
     * @throws SchemaChangeEnvironmentNoDatasourceException when the environment binds no datasource
     * @throws SchemaDriftScanInProgressException when a scan of it is already running anywhere in the
     *         cluster
     */
    SchemaDriftScanView scanNow(UUID organizationId, UUID actorId, UUID environmentId);

    /**
     * Accepts a drift finding. Idempotent on an already-acknowledged one.
     *
     * <p>An acknowledgement accepts the difference as it stands. A later scan that observes the same
     * object path with different values reopens it, because that difference was never accepted.
     *
     * @throws SchemaDriftFindingNotFoundException when the finding is not in this organization
     * @throws SchemaDriftFindingNotAcknowledgeableException when it has already been resolved
     */
    SchemaDriftFindingView acknowledge(UUID organizationId, UUID actorId, UUID findingId);
}
