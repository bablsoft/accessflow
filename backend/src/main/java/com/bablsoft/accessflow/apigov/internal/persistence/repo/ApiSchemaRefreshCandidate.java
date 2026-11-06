package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import java.time.Instant;
import java.util.UUID;

/** Projection of a schema opted into the scheduled refresh, with its connector's organization. */
public record ApiSchemaRefreshCandidate(UUID schemaId, UUID connectorId, UUID organizationId,
                                        int refreshIntervalMinutes, Instant lastRefreshAt) {
}
