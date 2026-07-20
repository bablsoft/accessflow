package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Submitter-facing reads over a connector's variables (AF-613) — what the request composer needs to
 * offer overrides, and what the submit path needs to validate them. Carries no secret, no
 * expression, and no algorithm.
 */
public interface ApiConnectorVariableLookupService {

    /** Every variable on the connector, projected to the submitter-safe fields. */
    List<ApiConnectorVariableSummaryView> summariesForConnector(UUID connectorId, UUID organizationId);

    /** The names a submitter is allowed to override. Anything outside this set is rejected. */
    Set<String> overridableNames(UUID connectorId, UUID organizationId);
}
