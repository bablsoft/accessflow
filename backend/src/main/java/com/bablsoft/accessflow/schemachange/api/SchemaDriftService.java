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

    PageResponse<SchemaDriftScanView> listScans(UUID organizationId, UUID environmentId, PageRequest pageRequest);

    PageResponse<SchemaDriftFindingView> listFindings(UUID organizationId, UUID environmentId,
                                                      SchemaDriftFindingStatus status, PageRequest pageRequest);

    /** Runs one scan of one environment now; a scan already in flight for it is a conflict. */
    SchemaDriftScanView scanNow(UUID organizationId, UUID actorId, UUID environmentId);

    SchemaDriftFindingView acknowledge(UUID organizationId, UUID actorId, UUID findingId);
}
