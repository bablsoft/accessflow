package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.datastax.oss.driver.api.core.CqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one native {@link CqlSession} per Cassandra/ScyllaDB datasource (the driver pools and
 * load-balances connections internally), the wide-column analogue of the host's JDBC
 * connection-pool manager. Sessions are evicted when the host calls
 * {@code QueryEngine.evictDatasource} on datasource config-change / deactivation.
 */
class CassandraSessionManager {

    private static final Logger log = LoggerFactory.getLogger(CassandraSessionManager.class);

    private final CassandraSessionFactory sessionFactory;
    private final Map<UUID, CqlSession> sessions = new ConcurrentHashMap<>();

    CassandraSessionManager(CassandraSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    CqlSession session(DatasourceConnectionDescriptor descriptor) {
        return sessions.computeIfAbsent(descriptor.id(), id -> {
            log.debug("Opening Cassandra session for datasource {}", id);
            return sessionFactory.open(descriptor);
        });
    }

    void evict(UUID datasourceId) {
        var session = sessions.remove(datasourceId);
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ex) {
                log.warn("Failed to close Cassandra session for datasource {}: {}",
                        datasourceId, ex.getMessage());
            }
        }
    }

    /** Close every cached session; called from {@code QueryEngine.shutdown()}. */
    void closeAll() {
        for (var datasourceId : sessions.keySet()) {
            evict(datasourceId);
        }
    }
}
