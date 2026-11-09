package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import java.time.Instant;
import java.util.UUID;

/** Projection of an active connector opted into the scheduled health probe (#894). */
public record ApiConnectorHealthCandidate(UUID connectorId, UUID organizationId,
                                          int healthCheckIntervalMinutes, Instant lastProbeAt) {
}
