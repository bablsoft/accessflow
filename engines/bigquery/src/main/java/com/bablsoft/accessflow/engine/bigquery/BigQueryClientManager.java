package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.google.cloud.bigquery.BigQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one native {@link BigQuery} client per BigQuery datasource — the warehouse analogue of
 * the host's JDBC connection-pool manager. The client itself is a cheap thread-safe HTTP stub,
 * but building one parses and validates the service-account key JSON, so caching avoids re-doing
 * that per query. {@link BigQuery} holds no closeable connection state (each call is an
 * independent HTTP request), so eviction simply drops the map entry; a config change or
 * deactivation reaches this via {@code QueryEngine.evictDatasource}.
 */
class BigQueryClientManager {

    private static final Logger log = LoggerFactory.getLogger(BigQueryClientManager.class);

    private final BigQueryClientFactory clientFactory;
    private final Map<UUID, BigQuery> clients = new ConcurrentHashMap<>();

    BigQueryClientManager(BigQueryClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    BigQuery client(DatasourceConnectionDescriptor descriptor) {
        return clients.computeIfAbsent(descriptor.id(), id -> {
            log.debug("Opening BigQuery client for datasource {}", id);
            return clientFactory.open(descriptor);
        });
    }

    void evict(UUID datasourceId) {
        if (clients.remove(datasourceId) != null) {
            log.debug("Evicted BigQuery client for datasource {}", datasourceId);
        }
    }

    /** Drop every cached client; called from {@code QueryEngine.shutdown()}. */
    void closeAll() {
        clients.clear();
    }
}
