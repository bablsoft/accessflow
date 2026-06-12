package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.SslMode;
import redis.clients.jedis.Connection;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/**
 * Builds a native {@link JedisPooled} from a {@link DatasourceConnectionDescriptor}. The password
 * is decrypted on demand and never stored beyond client construction. {@code sslMode != DISABLE}
 * selects a TLS connection ({@code rediss://}); the optional ACL {@code username}, the database
 * index (from {@code databaseName}, default {@code 0}), and the timeout / pool tuning come from the
 * datasource and the engine settings.
 */
final class RedisConnectionFactory {

    private RedisConnectionFactory() {
    }

    static JedisPooled open(DatasourceConnectionDescriptor descriptor, CredentialDecryptor credentials,
                            RedisEngineSettings settings) {
        var hostAndPort = new HostAndPort(descriptor.host(),
                descriptor.port() == null ? 6379 : descriptor.port());
        var clientConfig = clientConfig(descriptor, credentials, settings);
        GenericObjectPoolConfig<Connection> poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(Math.max(1, settings.maxPoolSize()));
        poolConfig.setTestOnBorrow(true);
        return new JedisPooled(poolConfig, hostAndPort, clientConfig);
    }

    private static JedisClientConfig clientConfig(DatasourceConnectionDescriptor descriptor,
                                                  CredentialDecryptor credentials,
                                                  RedisEngineSettings settings) {
        var builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(settings.connectTimeoutMillis())
                .socketTimeoutMillis(settings.socketTimeoutMillis())
                .ssl(descriptor.sslMode() != null && descriptor.sslMode() != SslMode.DISABLE)
                .database(resolveDatabase(descriptor));
        if (descriptor.username() != null && !descriptor.username().isBlank()) {
            builder.user(descriptor.username());
        }
        if (descriptor.passwordEncrypted() != null && !descriptor.passwordEncrypted().isBlank()) {
            builder.password(credentials.decrypt(descriptor.passwordEncrypted()));
        }
        return builder.build();
    }

    /** Redis database index from {@code databaseName} (e.g. {@code "3"}); defaults to {@code 0}. */
    static int resolveDatabase(DatasourceConnectionDescriptor descriptor) {
        var name = descriptor.databaseName();
        if (name == null || name.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(name.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
