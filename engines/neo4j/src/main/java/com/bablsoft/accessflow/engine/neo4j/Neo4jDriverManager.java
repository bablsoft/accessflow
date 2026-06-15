package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one native {@link Driver} per Neo4j datasource (the driver pools and routes Bolt
 * connections internally), the graph analogue of the host's JDBC connection-pool manager. Drivers
 * are evicted when the host calls {@code QueryEngine.evictDatasource} on datasource config-change /
 * deactivation.
 */
class Neo4jDriverManager {

    private static final Logger log = LoggerFactory.getLogger(Neo4jDriverManager.class);

    private final Neo4jDriverFactory driverFactory;
    private final Map<UUID, Driver> drivers = new ConcurrentHashMap<>();

    Neo4jDriverManager(Neo4jDriverFactory driverFactory) {
        this.driverFactory = driverFactory;
    }

    Driver driver(DatasourceConnectionDescriptor descriptor) {
        return drivers.computeIfAbsent(descriptor.id(), id -> {
            log.debug("Opening Neo4j driver for datasource {}", id);
            return driverFactory.open(descriptor);
        });
    }

    void evict(UUID datasourceId) {
        var driver = drivers.remove(datasourceId);
        if (driver != null) {
            try {
                driver.close();
            } catch (RuntimeException ex) {
                log.warn("Failed to close Neo4j driver for datasource {}: {}",
                        datasourceId, ex.getMessage());
            }
        }
    }

    /** Close every cached driver; called from {@code QueryEngine.shutdown()}. */
    void closeAll() {
        for (var datasourceId : drivers.keySet()) {
            evict(datasourceId);
        }
    }
}
