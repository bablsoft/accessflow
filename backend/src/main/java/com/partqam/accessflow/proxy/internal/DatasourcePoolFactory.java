package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DatasourceConnectionDescriptor;
import com.partqam.accessflow.core.api.JdbcCoordinatesFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DatasourcePoolFactory {

    private final CredentialEncryptionService encryptionService;
    private final JdbcCoordinatesFactory coordinatesFactory;
    private final ProxyPoolProperties properties;

    /**
     * Build a fresh Hikari pool from the descriptor. The persisted password is decrypted only
     * inside this method; the local plaintext reference is dropped before return. Hikari
     * retains its own copy for reconnects (unavoidable). Pool init is fail-fast: bad
     * credentials surface here as a {@link RuntimeException} from the Hikari constructor —
     * the caller wraps it in {@code PoolInitializationException}.
     */
    HikariDataSource createPool(DatasourceConnectionDescriptor descriptor) {
        var coords = coordinatesFactory.from(
                descriptor.dbType(),
                descriptor.host(),
                descriptor.port(),
                descriptor.databaseName(),
                descriptor.username(),
                descriptor.sslMode());

        var config = new HikariConfig();
        config.setPoolName(properties.poolNamePrefix() + descriptor.id());
        config.setJdbcUrl(coords.url());
        config.setDriverClassName(coords.driverClassName());
        config.setUsername(coords.username());
        config.setMaximumPoolSize(descriptor.connectionPoolSize());
        config.setConnectionTimeout(properties.connectionTimeout().toMillis());
        config.setIdleTimeout(properties.idleTimeout().toMillis());
        config.setMaxLifetime(properties.maxLifetime().toMillis());
        var leak = properties.leakDetectionThreshold().toMillis();
        if (leak > 0) {
            config.setLeakDetectionThreshold(leak);
        }

        String plaintext = encryptionService.decrypt(descriptor.passwordEncrypted());
        try {
            config.setPassword(plaintext);
            return new HikariDataSource(config);
        } finally {
            plaintext = null;
        }
    }
}
