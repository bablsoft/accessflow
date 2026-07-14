package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only connector references for other modules. Mirrors
 * {@code core.api.DatasourceLookupService}: the access (JIT) module uses it to list connectors a
 * user may request access to, deliberately not scoped to existing permissions.
 */
public interface ApiConnectorLookupService {

    /** @return the reference of a single connector, or empty when it does not exist. */
    Optional<ApiConnectorRef> findRef(UUID connectorId);

    /**
     * @return references to every <em>active</em> connector in the organization, ordered by name.
     */
    List<ApiConnectorRef> findActiveRefsByOrganization(UUID organizationId);
}
