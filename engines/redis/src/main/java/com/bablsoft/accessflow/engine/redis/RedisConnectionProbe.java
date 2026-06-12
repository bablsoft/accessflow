package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Connection test for a Redis datasource: opens a short-lived client and issues {@code PING}. The
 * key-value-engine analogue of the JDBC {@code SELECT 1} probe.
 */
class RedisConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionProbe.class);

    private final CredentialDecryptor credentials;
    private final RedisEngineSettings settings;

    RedisConnectionProbe(CredentialDecryptor credentials, RedisEngineSettings settings) {
        this.credentials = credentials;
        this.settings = settings;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try (var client = RedisConnectionFactory.open(descriptor, credentials, settings)) {
            client.ping();
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (JedisException | IllegalArgumentException e) {
            log.warn("Redis connection test failed for datasource {}: {}", descriptor.id(),
                    e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }
}
