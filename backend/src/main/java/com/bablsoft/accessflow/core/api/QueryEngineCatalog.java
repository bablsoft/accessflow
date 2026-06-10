package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Resolves the {@link QueryEngine} for a non-JDBC {@link DbType} on demand. The implementation
 * (proxy module) looks up the connector manifest, ensures the engine's shaded plugin JAR is cached
 * (downloading and SHA-256-verifying it through the same pipeline as JDBC driver JARs), loads it
 * into an isolated classloader, discovers the {@link QueryEngine} via
 * {@link java.util.ServiceLoader}, initializes it once, and caches the instance for the
 * application lifetime.
 */
public interface QueryEngineCatalog {

    /**
     * The initialized engine for {@code dbType}.
     *
     * @throws DriverResolutionException when the plugin JAR cannot be resolved — offline with no
     *         cached JAR, download failure, checksum mismatch, or no matching {@link
     *         QueryEngine#engineId()} provider inside the JAR.
     */
    QueryEngine engineFor(DbType dbType);

    /**
     * True when {@code dbType}'s connector manifest declares a non-RELATIONAL category, i.e.
     * queries against it are parsed and executed by an engine plugin rather than the JDBC/SQL
     * path. Metadata-only — never downloads or loads a plugin.
     */
    boolean isEngineManaged(DbType dbType);

    /**
     * Notify already-loaded engines that a datasource's connection config changed or the
     * datasource was deactivated. Never triggers a plugin download.
     */
    void evictDatasource(UUID datasourceId);
}
