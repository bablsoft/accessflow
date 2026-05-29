package com.bablsoft.accessflow.proxy.api;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Caches one HikariCP pool per active datasource, keyed by datasource id. Pools are created
 * lazily on the first {@link #resolve(UUID)} call and live until either {@link #evict(UUID)}
 * is invoked (e.g. on a datasource update or deactivation event) or the application shuts down.
 *
 * <p>When the datasource has a read replica configured, a sibling pool is built on demand by
 * {@link #resolveReplica(UUID)}; both pools are evicted together. Implementations decrypt the
 * persisted password no earlier than pool initialization and do not retain a reference to the
 * plaintext beyond the call. The returned {@link DataSource} is the Hikari pool itself; callers
 * obtain connections via the standard JDBC idiom:
 * <pre>{@code
 * try (var connection = manager.resolve(id).getConnection()) { ... }
 * }</pre>
 */
public interface DatasourceConnectionPoolManager {

    /**
     * Return the cached primary pool for {@code datasourceId}, creating it on first use.
     *
     * @throws DatasourceUnavailableException if the datasource is missing or inactive.
     * @throws PoolInitializationException if Hikari cannot establish the first connection
     *         (e.g. wrong credentials, host unreachable).
     */
    DataSource resolve(UUID datasourceId);

    /**
     * Return the cached read-replica pool for {@code datasourceId} if and only if the datasource
     * has a replica configured. Empty when no replica is set; never falls back to the primary
     * (the caller's routing layer owns that decision).
     *
     * @throws DatasourceUnavailableException if the datasource is missing or inactive.
     * @throws PoolInitializationException if Hikari cannot establish the first connection to
     *         the replica.
     */
    Optional<DataSource> resolveReplica(UUID datasourceId);

    /**
     * Close and remove both the primary and replica pools for {@code datasourceId}. No-op for
     * either side when no pool is cached.
     */
    void evict(UUID datasourceId);

    /**
     * Return live gauges for the cached primary pool of {@code datasourceId}, or empty when no
     * pool is currently cached. Unlike {@link #resolve(UUID)} this never creates a pool — reading
     * health metrics must not trigger a connection attempt against a (possibly unreachable)
     * customer database.
     */
    Optional<DatasourcePoolStats> poolStats(UUID datasourceId);
}
