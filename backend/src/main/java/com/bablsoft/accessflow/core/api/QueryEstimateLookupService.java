package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookup of a query's persisted pre-flight cost estimate (issue AF-624). Consumed by the
 * workflow module's routing-condition context builder and the read API — pure read, no
 * computation.
 */
public interface QueryEstimateLookupService {

    Optional<QueryEstimateSnapshot> findByQueryRequestId(UUID queryRequestId);
}
