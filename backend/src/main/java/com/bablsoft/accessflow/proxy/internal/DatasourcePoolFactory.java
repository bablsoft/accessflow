package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.CustomDriverNotFoundException;
import com.bablsoft.accessflow.core.api.CustomJdbcDriverService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.JdbcCoordinatesFactory;
import com.bablsoft.accessflow.core.api.ResolvedDriver;
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
    private final DriverCatalogService driverCatalog;
    private final CustomJdbcDriverService customJdbcDriverService;

    /**
     * Build a fresh Hikari pool from the descriptor. The persisted password is decrypted only
     * inside this method; the local plaintext reference is dropped before return. Hikari
     * retains its own copy for reconnects (unavoidable). The customer-DB JDBC driver is
     * resolved through {@link DriverCatalogService}, which downloads + caches it on first use
     * and loads it into a {@link DbType}-scoped child classloader. For datasources that
     * reference an admin-uploaded driver (via {@code custom_driver_id}), the per-driver
     * {@link java.net.URLClassLoader} is used instead — so two datasources pointing at
     * different uploaded JARs remain isolated. Pool init is fail-fast: driver-resolution
     * failures surface as {@code DriverResolutionException}; bad credentials surface here as
     * a {@link RuntimeException} from the Hikari constructor — the caller wraps the latter in
     * {@code PoolInitializationException}.
     */
    HikariDataSource createPool(DatasourceConnectionDescriptor descriptor) {
        ResolvedDriver resolved = resolveDriver(descriptor);
        String jdbcUrl = resolveJdbcUrl(descriptor);
        String username = descriptor.username();

        var config = new HikariConfig();
        config.setPoolName(properties.poolNamePrefix() + descriptor.id());
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName(resolved.driverClassName());
        config.setUsername(username);
        config.setMaximumPoolSize(descriptor.connectionPoolSize());
        config.setConnectionTimeout(properties.connectionTimeout().toMillis());
        config.setIdleTimeout(properties.idleTimeout().toMillis());
        config.setMaxLifetime(properties.maxLifetime().toMillis());
        var leak = properties.leakDetectionThreshold().toMillis();
        if (leak > 0) {
            config.setLeakDetectionThreshold(leak);
        }

        String plaintext = encryptionService.decrypt(descriptor.passwordEncrypted());
        var previousLoader = Thread.currentThread().getContextClassLoader();
        try {
            // HikariCP loads the driver via the thread's context classloader. Swap it
            // to the per-DbType (or per-custom-driver) URLClassLoader so customer-DB drivers
            // resolve correctly without polluting the application classloader.
            Thread.currentThread().setContextClassLoader(resolved.classLoader());
            config.setPassword(plaintext);
            return new HikariDataSource(config);
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            plaintext = null;
        }
    }

    private ResolvedDriver resolveDriver(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.customDriverId() != null) {
            var customDescriptor = customJdbcDriverService
                    .findById(descriptor.customDriverId(), descriptor.organizationId())
                    .orElseThrow(() -> new CustomDriverNotFoundException(descriptor.customDriverId()));
            return driverCatalog.resolveCustom(customDescriptor);
        }
        return driverCatalog.resolve(descriptor.dbType());
    }

    private String resolveJdbcUrl(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.jdbcUrlOverride() != null && !descriptor.jdbcUrlOverride().isBlank()) {
            return descriptor.jdbcUrlOverride();
        }
        Integer port = descriptor.port();
        return coordinatesFactory.from(
                descriptor.dbType(),
                descriptor.host(),
                port != null ? port : 0,
                descriptor.databaseName(),
                descriptor.username(),
                descriptor.sslMode()).url();
    }
}
