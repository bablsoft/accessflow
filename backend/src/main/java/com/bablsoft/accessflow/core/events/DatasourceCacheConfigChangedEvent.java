package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a datasource's SELECT result-cache settings change (enabled flag or TTL, AF-457).
 * Consumers purge cached results for the datasource; unlike {@link DatasourceConfigChangedEvent}
 * this must not evict connection pools.
 */
public record DatasourceCacheConfigChangedEvent(UUID datasourceId) {
}
