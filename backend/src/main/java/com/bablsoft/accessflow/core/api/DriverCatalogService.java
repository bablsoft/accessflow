package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public interface DriverCatalogService {

    /** Bundled drivers only — backwards-compatible with pre-#94 callers. */
    List<DriverTypeInfo> list();

    /**
     * Bundled drivers plus the organization's admin-uploaded drivers. Bundled rows precede
     * uploaded rows; uploaded rows are sorted by upload time (newest first).
     */
    List<DriverTypeInfo> list(UUID organizationId, List<CustomDriverDescriptor> uploaded);

    /**
     * Every connector in the catalog (the built-in dialects and any additional engines), with
     * its install status derived from driver-JAR cache presence. Backs the connector
     * marketplace ({@code GET /datasources/connectors}).
     */
    List<DriverTypeInfo> listConnectors();

    /**
     * Install a connector by downloading + SHA-256-verifying + caching its driver JAR. Bundled
     * connectors are a no-op (always {@code READY}). Returns the connector's refreshed catalog
     * row. Throws {@link ConnectorNotFoundException} for an unknown id and
     * {@link DriverResolutionException} on download/checksum failure.
     */
    DriverTypeInfo install(String connectorId);

    /**
     * Resolve the driver for a catalog connector identified by id, loading the cached JAR into a
     * per-connector {@link ClassLoader}. Used by the proxy for {@link DbType#CUSTOM} datasources
     * that reference a connector ({@code connector_id}).
     */
    ResolvedDriver resolveConnector(String connectorId);

    /**
     * Build the JDBC URL for a connector-backed datasource by substituting {@code host},
     * {@code port}, {@code database_name} into the connector's manifest template. Throws
     * {@link ConnectorNotFoundException} for an unknown id.
     */
    String connectorJdbcUrl(String connectorId, String host, int port, String databaseName);

    /**
     * Resolve a bundled driver. Used by datasources whose {@code custom_driver_id} is null and
     * whose {@code db_type} is one of the bundled five.
     */
    ResolvedDriver resolve(DbType dbType);

    /**
     * Resolve an admin-uploaded driver. The JAR is loaded into a {@link ClassLoader} keyed by
     * {@link CustomDriverDescriptor#id()} so two datasources pointing at different uploaded
     * drivers (even with identical {@link DbType}) get isolated classloaders.
     */
    ResolvedDriver resolveCustom(CustomDriverDescriptor descriptor);

    /** Drops any cached classloader for the given uploaded driver. Idempotent. */
    void evictCustom(UUID customDriverId);
}
