package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one {@link SearchTransport} (REST client) per Elasticsearch / OpenSearch datasource — the
 * search-engine analogue of the host's JDBC connection-pool manager (the REST client pools HTTP
 * connections internally). Transports are evicted when the host calls
 * {@code QueryEngine.evictDatasource} on datasource config-change / deactivation.
 */
class SearchClientManager {

    private static final Logger log = LoggerFactory.getLogger(SearchClientManager.class);

    private final SearchTransportFactory factory;
    private final Map<UUID, SearchTransport> transports = new ConcurrentHashMap<>();

    SearchClientManager(SearchTransportFactory factory) {
        this.factory = factory;
    }

    SearchTransport transport(DatasourceConnectionDescriptor descriptor) {
        return transports.computeIfAbsent(descriptor.id(), id -> {
            log.debug("Opening search REST client for datasource {}", id);
            return factory.create(descriptor);
        });
    }

    void evict(UUID datasourceId) {
        var transport = transports.remove(datasourceId);
        if (transport != null) {
            try {
                transport.close();
            } catch (RuntimeException ex) {
                log.warn("Failed to close search REST client for datasource {}: {}",
                        datasourceId, ex.getMessage());
            }
        }
    }

    /** Close every cached transport; called from {@code QueryEngine.shutdown()}. */
    void closeAll() {
        for (var datasourceId : transports.keySet()) {
            evict(datasourceId);
        }
    }
}
