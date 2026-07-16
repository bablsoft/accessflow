package com.bablsoft.accessflow.proxy.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Caches one HikariCP pool per active datasource, keyed by datasource id. Pools are created
 * lazily on the first {@link #resolve(UUID)} call and live until either {@link #evict(UUID)}
 * is invoked (e.g. on a datasource update or deactivation event) or the application shuts down.
 *
 * <p>When the datasource has read replicas configured (AF-457), a sibling pool per endpoint is
 * built on demand by {@link #resolveReplica(UUID, UUID)}; all pools are evicted together.
 * Implementations decrypt the persisted password no earlier than pool initialization and do not
 * retain a reference to the plaintext beyond the call. The returned {@link DataSource} is the
 * Hikari pool itself; callers obtain connections via the standard JDBC idiom:
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
     * Return the datasource's read-replica endpoints in configured order, without creating any
     * pool. Empty when the datasource has no replicas.
     *
     * @throws DatasourceUnavailableException if the datasource is missing or inactive.
     */
    List<ReplicaEndpointRef> replicaEndpoints(UUID datasourceId);

    /**
     * Return the cached pool for one read-replica endpoint of {@code datasourceId}, creating it
     * on first use. Never falls back to the primary or a sibling endpoint (the caller's routing
     * layer owns that decision).
     *
     * @throws DatasourceUnavailableException if the datasource is missing or inactive, or the
     *         endpoint id is not one of its replicas.
     * @throws PoolInitializationException if Hikari cannot establish the first connection to
     *         the replica.
     */
    DataSource resolveReplica(UUID datasourceId, UUID endpointId);

    /**
     * Close and remove the primary and every replica pool for {@code datasourceId}. No-op for
     * any side with no cached pool.
     */
    void evict(UUID datasourceId);

    /**
     * Return live gauges for the cached primary pool of {@code datasourceId}, or empty when no
     * pool is currently cached. Unlike {@link #resolve(UUID)} this never creates a pool — reading
     * health metrics must not trigger a connection attempt against a (possibly unreachable)
     * customer database.
     */
    Optional<DatasourcePoolStats> poolStats(UUID datasourceId);

    /**
     * Return live gauges for the cached pool of one replica endpoint, or empty when that
     * endpoint's pool is not currently cached. Never creates a pool (same guarantee as
     * {@link #poolStats(UUID)}).
     */
    Optional<DatasourcePoolStats> replicaPoolStats(UUID datasourceId, UUID endpointId);
}
