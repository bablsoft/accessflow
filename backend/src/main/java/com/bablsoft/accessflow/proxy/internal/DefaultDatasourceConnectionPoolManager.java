package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.DatasourcePoolStats;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.proxy.api.PoolInitializationException;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
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
    private final ConcurrentMap<UUID, HikariDataSource> replicaPools = new ConcurrentHashMap<>();

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
    public Optional<DataSource> resolveReplica(UUID datasourceId) {
        var cached = replicaPools.get(datasourceId);
        if (cached != null && !cached.isClosed()) {
            return Optional.of(cached);
        }
        var descriptor = loadActiveDescriptor(datasourceId);
        if (!descriptor.hasReadReplica()) {
            return Optional.empty();
        }
        var pool = replicaPools.compute(datasourceId, (id, current) -> {
            if (current != null && !current.isClosed()) {
                return current;
            }
            try {
                var created = poolFactory.createReplicaPool(descriptor);
                log.info("Created Hikari replica pool {} for datasource {}",
                        created.getPoolName(), id);
                return created;
            } catch (RuntimeException ex) {
                throw new PoolInitializationException(msg("error.pool_initialization_failed"), ex);
            }
        });
        return Optional.of(pool);
    }

    private com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor loadActiveDescriptor(
            UUID datasourceId) {
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
        closeAndRemove(pools, datasourceId, "primary");
        closeAndRemove(replicaPools, datasourceId, "replica");
    }

    @Override
    public Optional<DatasourcePoolStats> poolStats(UUID datasourceId) {
        var pool = pools.get(datasourceId);
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

    private static void closeAndRemove(ConcurrentMap<UUID, HikariDataSource> map, UUID id,
                                       String label) {
        var removed = map.remove(id);
        if (removed != null && !removed.isClosed()) {
            log.info("Evicting Hikari {} pool for datasource {}", label, id);
            removed.close();
        }
    }

    @PreDestroy
    void shutdown() {
        closeAll(pools);
        closeAll(replicaPools);
    }

    private static void closeAll(ConcurrentMap<UUID, HikariDataSource> map) {
        map.forEach((id, pool) -> {
            if (!pool.isClosed()) {
                pool.close();
            }
        });
        map.clear();
    }
}
