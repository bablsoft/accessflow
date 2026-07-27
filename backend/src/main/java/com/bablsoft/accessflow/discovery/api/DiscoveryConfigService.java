package com.bablsoft.accessflow.discovery.api;

import java.util.UUID;

/**
 * Admin management of a datasource's discovery settings (AF-623). Both methods validate the
 * datasource belongs to the organization (404 otherwise).
 */
public interface DiscoveryConfigService {

    /** Returns the persisted config, or a synthesized default-disabled view when none exists. */
    DiscoveryScanConfigView get(UUID datasourceId, UUID organizationId);

    /** Creates or updates the config row; {@code null} command fields keep the current value. */
    DiscoveryScanConfigView upsert(UUID datasourceId, UUID organizationId,
                                   UpsertDiscoveryConfigCommand command);
}
