package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a datasource's connection-relevant fields change (host, port, database name,
 * username, password, SSL mode, or pool size). Listeners — e.g. the proxy module's pool
 * eviction listener — invalidate any cached state keyed by the datasource id.
 */
public record DatasourceConfigChangedEvent(UUID datasourceId) {
}
