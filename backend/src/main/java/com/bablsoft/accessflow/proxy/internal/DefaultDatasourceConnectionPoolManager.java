package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.DatasourcePoolStats;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.proxy.api.PoolInitializationException;
import com.bablsoft.accessflow.proxy.api.ReplicaEndpointRef;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
class DefaultDatasourceConnectionPoolManager implements DatasourceConnectionPoolManager {

    private static final Logger log = LoggerFactory.getLogger(
            DefaultDatasourceConnectionPoolManager.class);

    private final DatasourceLookupService datasourceLookupService;
    private final DatasourcePoolFactory poolFactory;
    private final MessageSource messageSource;

    private final ConcurrentMap<UUID, HikariDataSource> pools = new ConcurrentHashMap<>();
    /** Per-datasource map of replica pools keyed by endpoint id (AF-457). */
    private final ConcurrentMap<UUID, ConcurrentMap<UUID, HikariDataSource>> replicaPools =
            new ConcurrentHashMap<>();

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    public DataSource resolve(UUID datasourceId) {
        var cached = pools.get(datasourceId);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }
        return pools.compute(datasourceId, (id, current) -> {
            if (current != null && !current.isClosed()) {
                return current;
            }
            var descriptor = loadActiveDescriptor(id);
            try {
                var pool = poolFactory.createPool(descriptor);
                log.info("Created Hikari pool {} for datasource {}", pool.getPoolName(), id);
                return pool;
            } catch (RuntimeException ex) {
                throw new PoolInitializationException(msg("error.pool_initialization_failed"), ex);
            }
        });
    }

    @Override
    public List<ReplicaEndpointRef> replicaEndpoints(UUID datasourceId) {
        var descriptor = loadActiveDescriptor(datasourceId);
        return descriptor.readReplicas().stream()
                .map(endpoint -> new ReplicaEndpointRef(endpoint.id(), label(endpoint.jdbcUrl())))
                .toList();
    }

    @Override
    public DataSource resolveReplica(UUID datasourceId, UUID endpointId) {
        var cached = replicaPools.get(datasourceId);
        if (cached != null) {
            var pool = cached.get(endpointId);
            if (pool != null && !pool.isClosed()) {
                return pool;
            }
        }
        var descriptor = loadActiveDescriptor(datasourceId);
        var endpoint = descriptor.readReplicas().stream()
                .filter(candidate -> candidate.id().equals(endpointId))
                .findFirst()
                .orElseThrow(() -> new DatasourceUnavailableException(
                        msg("error.datasource_unavailable_not_found")));
        var byEndpoint = replicaPools.computeIfAbsent(datasourceId,
                id -> new ConcurrentHashMap<>());
        return byEndpoint.compute(endpointId, (id, current) -> {
            if (current != null && !current.isClosed()) {
                return current;
            }
            try {
                var created = poolFactory.createReplicaPool(descriptor, endpoint);
                log.info("Created Hikari replica pool {} for datasource {}",
                        created.getPoolName(), datasourceId);
                return created;
            } catch (RuntimeException ex) {
                throw new PoolInitializationException(msg("error.pool_initialization_failed"), ex);
            }
        });
    }

    private DatasourceConnectionDescriptor loadActiveDescriptor(UUID datasourceId) {
        var descriptor = datasourceLookupService.findById(datasourceId)
                .orElseThrow(() -> new DatasourceUnavailableException(
                        msg("error.datasource_unavailable_not_found")));
        if (!descriptor.active()) {
            throw new DatasourceUnavailableException(msg("error.datasource_unavailable_inactive"));
        }
        return descriptor;
    }

    @Override
    public void evict(UUID datasourceId) {
        var removed = pools.remove(datasourceId);
        if (removed != null && !removed.isClosed()) {
            log.info("Evicting Hikari primary pool for datasource {}", datasourceId);
            removed.close();
        }
        var removedReplicas = replicaPools.remove(datasourceId);
        if (removedReplicas != null) {
            removedReplicas.forEach((endpointId, pool) -> {
                if (!pool.isClosed()) {
                    log.info("Evicting Hikari replica pool for datasource {} endpoint {}",
                            datasourceId, endpointId);
                    pool.close();
                }
            });
        }
    }

    @Override
    public Optional<DatasourcePoolStats> poolStats(UUID datasourceId) {
        return toStats(pools.get(datasourceId));
    }

    @Override
    public Optional<DatasourcePoolStats> replicaPoolStats(UUID datasourceId, UUID endpointId) {
        var byEndpoint = replicaPools.get(datasourceId);
        return toStats(byEndpoint == null ? null : byEndpoint.get(endpointId));
    }

    private static Optional<DatasourcePoolStats> toStats(HikariDataSource pool) {
        if (pool == null || pool.isClosed()) {
            return Optional.empty();
        }
        var mx = pool.getHikariPoolMXBean();
        return Optional.of(new DatasourcePoolStats(
                mx.getActiveConnections(),
                mx.getIdleConnections(),
                mx.getThreadsAwaitingConnection(),
                mx.getTotalConnections(),
                pool.getMaximumPoolSize()));
    }

    /**
     * Visits every currently-cached, open replica pool. Used by {@link ReplicaHealthProber} to
     * probe only endpoints that have actually served traffic on this node — probing never creates
     * pools (same rule as {@link #poolStats(UUID)}).
     */
    void forEachCachedReplicaPool(ReplicaPoolVisitor visitor) {
        replicaPools.forEach((datasourceId, byEndpoint) ->
                byEndpoint.forEach((endpointId, pool) -> {
                    if (!pool.isClosed()) {
                        visitor.visit(datasourceId, endpointId, pool);
                    }
                }));
    }

    @FunctionalInterface
    interface ReplicaPoolVisitor {
        void visit(UUID datasourceId, UUID endpointId, DataSource pool);
    }

    /**
     * Redacted display form of a replica JDBC URL: everything between the authority separator and
     * the first path/query delimiter (host[:port]), with any {@code user@} userinfo dropped. Never
     * exposes credentials or query parameters.
     */
    static String label(String url) {
        int authorityStart = url.indexOf("//");
        if (authorityStart < 0) {
            int colon = url.lastIndexOf(':');
            return colon > 0 ? url.substring(0, colon) : url;
        }
        int start = authorityStart + 2;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == ';') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        return at >= 0 ? authority.substring(at + 1) : authority;
    }

    @PreDestroy
    void shutdown() {
        pools.forEach((id, pool) -> {
            if (!pool.isClosed()) {
                pool.close();
            }
        });
        pools.clear();
        replicaPools.forEach((id, byEndpoint) -> byEndpoint.forEach((endpointId, pool) -> {
            if (!pool.isClosed()) {
                pool.close();
            }
        }));
        replicaPools.clear();
    }
}
