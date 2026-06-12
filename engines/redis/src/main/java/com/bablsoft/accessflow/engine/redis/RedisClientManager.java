package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one native {@link JedisPooled} per Redis datasource (Jedis pools connections internally),
 * the key-value-engine analogue of the host's JDBC connection-pool manager. Clients are evicted
 * when the host calls {@code QueryEngine.evictDatasource} on datasource config-change / deactivation.
 */
class RedisClientManager {

    private static final Logger log = LoggerFactory.getLogger(RedisClientManager.class);

    private final CredentialDecryptor credentials;
    private final RedisEngineSettings settings;
    private final Map<UUID, JedisPooled> clients = new ConcurrentHashMap<>();

    RedisClientManager(CredentialDecryptor credentials, RedisEngineSettings settings) {
        this.credentials = credentials;
        this.settings = settings;
    }

    JedisPooled client(DatasourceConnectionDescriptor descriptor) {
        return clients.computeIfAbsent(descriptor.id(),
                id -> RedisConnectionFactory.open(descriptor, credentials, settings));
    }

    void evict(UUID datasourceId) {
        var client = clients.remove(datasourceId);
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ex) {
                log.warn("Failed to close JedisPooled for datasource {}: {}", datasourceId,
                        ex.getMessage());
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
