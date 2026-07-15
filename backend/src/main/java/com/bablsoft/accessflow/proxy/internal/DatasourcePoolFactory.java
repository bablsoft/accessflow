package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.CustomDriverNotFoundException;
import com.bablsoft.accessflow.core.api.CustomJdbcDriverService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.JdbcCoordinatesFactory;
import com.bablsoft.accessflow.core.api.ResolvedDriver;
import com.bablsoft.accessflow.core.api.SecretResolutionService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DatasourcePoolFactory {

    private final SecretResolutionService secretResolutionService;
    private final JdbcCoordinatesFactory coordinatesFactory;
    private final ProxyPoolProperties properties;
    private final DriverCatalogService driverCatalog;
    private final CustomJdbcDriverService customJdbcDriverService;

    /**
     * Build a fresh Hikari pool from the descriptor. The persisted credential is resolved to
     * plaintext only inside this method — local AES decryption, or an external secret-store
     * fetch when the stored value is a secret reference (AF-448) — and the local plaintext
     * reference is dropped before return. Hikari
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
        return buildPool(
                descriptor,
                resolved,
                descriptor.id().toString(),
                resolveJdbcUrl(descriptor),
                descriptor.username(),
                descriptor.passwordEncrypted(),
                descriptor.connectionPoolSize());
    }

    /**
     * Build a Hikari pool against the read replica URL/credentials on the descriptor. Reuses
     * the same driver class as the primary (so the replica must be the same engine). Caller is
     * responsible for ensuring {@link DatasourceConnectionDescriptor#hasReadReplica()} is true.
     */
    HikariDataSource createReplicaPool(DatasourceConnectionDescriptor descriptor) {
        ResolvedDriver resolved = resolveDriver(descriptor);
        String username = descriptor.readReplicaUsername() != null
                ? descriptor.readReplicaUsername()
                : descriptor.username();
        String encrypted = descriptor.readReplicaPasswordEncrypted() != null
                ? descriptor.readReplicaPasswordEncrypted()
                : descriptor.passwordEncrypted();
        return buildPool(
                descriptor,
                resolved,
                descriptor.id() + "-replica",
                descriptor.readReplicaJdbcUrl(),
                username,
                encrypted,
                descriptor.connectionPoolSize());
    }

    private HikariDataSource buildPool(DatasourceConnectionDescriptor descriptor,
                                       ResolvedDriver resolved, String idSuffix, String jdbcUrl,
                                       String username, String passwordEncrypted, int poolSize) {
        var config = new HikariConfig();
        config.setPoolName(properties.poolNamePrefix() + idSuffix);
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName(resolved.driverClassName());
        config.setUsername(username);
        config.setMaximumPoolSize(poolSize);
        config.setConnectionTimeout(properties.connectionTimeout().toMillis());
        config.setIdleTimeout(properties.idleTimeout().toMillis());
        config.setMaxLifetime(properties.maxLifetime().toMillis());
        var leak = properties.leakDetectionThreshold().toMillis();
        if (leak > 0) {
            config.setLeakDetectionThreshold(leak);
        }

        String plaintext = secretResolutionService.resolve(
                passwordEncrypted, descriptor.id(), descriptor.organizationId());
        var previousLoader = Thread.currentThread().getContextClassLoader();
        try {
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
        if (descriptor.connectorId() != null && !descriptor.connectorId().isBlank()) {
            return driverCatalog.resolveConnector(descriptor.connectorId());
        }
        return driverCatalog.resolve(descriptor.dbType());
    }

    private String resolveJdbcUrl(DatasourceConnectionDescriptor descriptor) {
        if (descriptor.jdbcUrlOverride() != null && !descriptor.jdbcUrlOverride().isBlank()) {
            return descriptor.jdbcUrlOverride();
        }
        Integer port = descriptor.port();
        // Connector-backed CUSTOM datasources build their URL from the connector's manifest
        // template (host/port/database substitution) — the dialect-aware coordinates factory
        // doesn't know the connector's template, so the catalog service builds it.
        if (descriptor.connectorId() != null && !descriptor.connectorId().isBlank()) {
            return driverCatalog.connectorJdbcUrl(
                    descriptor.connectorId(),
                    descriptor.host(),
                    port != null ? port : 0,
                    descriptor.databaseName());
        }
        return coordinatesFactory.from(
                descriptor.dbType(),
                descriptor.host(),
                port != null ? port : 0,
                descriptor.databaseName(),
                descriptor.username(),
                descriptor.sslMode()).url();
    }
}
