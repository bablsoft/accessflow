package com.partqam.accessflow.proxy.api;

import java.util.UUID;
import javax.sql.DataSource;

/**
 * Caches one HikariCP pool per active datasource, keyed by datasource id. Pools are created
 * lazily on the first {@link #resolve(UUID)} call and live until either {@link #evict(UUID)}
 * is invoked (e.g. on a datasource update or deactivation event) or the application shuts down.
 *
 * <p>Implementations decrypt the persisted password no earlier than pool initialization and do
 * not retain a reference to the plaintext beyond the call. The returned {@link DataSource} is
 * the Hikari pool itself; callers obtain connections via the standard JDBC idiom:
 * <pre>{@code
 * try (var connection = manager.resolve(id).getConnection()) { ... }
 * }</pre>
 */
public interface DatasourceConnectionPoolManager {

    /**
     * Return the cached pool for {@code datasourceId}, creating it on first use.
     *
     * @throws DatasourceUnavailableException if the datasource is missing or inactive.
     * @throws PoolInitializationException if Hikari cannot establish the first connection
     *         (e.g. wrong credentials, host unreachable).
     */
    DataSource resolve(UUID datasourceId);

    /**
     * Close and remove the pool for {@code datasourceId}. No-op if no pool is cached.
     */
    void evict(UUID datasourceId);
}
