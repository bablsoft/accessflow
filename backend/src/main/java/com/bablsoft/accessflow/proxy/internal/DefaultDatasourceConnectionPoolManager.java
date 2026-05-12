package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
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
            var descriptor = datasourceLookupService.findById(id)
                    .orElseThrow(() -> new DatasourceUnavailableException(
                            msg("error.datasource_unavailable_not_found")));
            if (!descriptor.active()) {
                throw new DatasourceUnavailableException(msg("error.datasource_unavailable_inactive"));
            }
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
    public void evict(UUID datasourceId) {
        var removed = pools.remove(datasourceId);
        if (removed != null && !removed.isClosed()) {
            log.info("Evicting Hikari pool for datasource {}", datasourceId);
            removed.close();
        }
    }

    @PreDestroy
    void shutdown() {
        pools.forEach((id, pool) -> {
            if (!pool.isClosed()) {
                pool.close();
            }
        });
        pools.clear();
    }
}
