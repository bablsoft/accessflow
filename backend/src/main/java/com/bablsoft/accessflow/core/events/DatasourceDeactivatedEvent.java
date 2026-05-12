package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published when a datasource transitions from active to inactive. Listeners — e.g. the proxy
 * module's pool eviction listener — release any resources tied to the datasource.
 */
public record DatasourceDeactivatedEvent(UUID datasourceId) {
}
