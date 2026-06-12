package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one native {@link DynamoDbClient} per DynamoDB datasource (the SDK client is thread-safe
 * and manages its own HTTP connection pool), the key-value analogue of the host's JDBC
 * connection-pool manager. Clients are evicted when the host calls
 * {@code QueryEngine.evictDatasource} on datasource config-change / deactivation.
 */
class DynamoDbClientManager {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbClientManager.class);

    private final DynamoDbClientFactory clientFactory;
    private final Map<UUID, DynamoDbClient> clients = new ConcurrentHashMap<>();

    DynamoDbClientManager(DynamoDbClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    DynamoDbClient client(DatasourceConnectionDescriptor descriptor) {
        return clients.computeIfAbsent(descriptor.id(), id -> {
            log.debug("Opening DynamoDB client for datasource {}", id);
            return clientFactory.open(descriptor);
        });
    }

    void evict(UUID datasourceId) {
        var client = clients.remove(datasourceId);
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ex) {
                log.warn("Failed to close DynamoDB client for datasource {}: {}",
                        datasourceId, ex.getMessage());
            }
        }
    }

    /** Close every cached client; called from {@code QueryEngine.shutdown()}. */
    void closeAll() {
        for (var datasourceId : clients.keySet()) {
            evict(datasourceId);
        }
    }
}
