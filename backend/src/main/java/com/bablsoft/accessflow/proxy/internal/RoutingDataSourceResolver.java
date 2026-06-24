package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.proxy.api.PoolInitializationException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.UUID;

/**
 * Picks the right HikariCP pool for a query: {@link QueryType#SELECT} goes to the read replica
 * when one is configured, with fall-back to the primary on connection failure. All non-SELECT
 * statements (DML/DDL and transactional batches) always hit the primary. Replica fall-back emits
 * a {@link AuditAction#DATASOURCE_REPLICA_FALLBACK} audit row but never propagates the underlying
 * failure to the caller — the query still runs against the primary.
 */
@Component
@RequiredArgsConstructor
class RoutingDataSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(RoutingDataSourceResolver.class);

    private final DatasourceConnectionPoolManager poolManager;
    private final DatasourceLookupService datasourceLookupService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final ObservationRegistry observationRegistry;

    /**
     * Acquires a pooled connection, traced as {@code accessflow.datasource.acquire} (AF-454) so the
     * pool-acquire latency is a child span of the surrounding {@code accessflow.query.execute}.
     */
    Connection acquire(UUID datasourceId, QueryType queryType) throws SQLException {
        Observation observation = Observation.createNotStarted("accessflow.datasource.acquire", observationRegistry)
                .lowCardinalityKeyValue("query_type", queryType.name())
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            Connection connection = doAcquire(datasourceId, queryType);
            observation.lowCardinalityKeyValue("outcome", "success");
            return connection;
        } catch (SQLException | RuntimeException ex) {
            observation.lowCardinalityKeyValue("outcome", "failure");
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }

    private Connection doAcquire(UUID datasourceId, QueryType queryType) throws SQLException {
        if (queryType == QueryType.SELECT) {
            try {
                var replica = poolManager.resolveReplica(datasourceId);
                if (replica.isPresent()) {
                    try {
                        return replica.get().getConnection();
                    } catch (SQLException | RuntimeException ex) {
                        recordFallback(datasourceId, ex);
                    }
                }
            } catch (PoolInitializationException ex) {
                recordFallback(datasourceId, ex);
            }
        }
        return poolManager.resolve(datasourceId).getConnection();
    }

    private void recordFallback(UUID datasourceId, Throwable ex) {
        log.warn("Read-replica connection failed for datasource {}; falling back to primary: {}",
                datasourceId, ex.getMessage());
        try {
            var organizationId = datasourceLookupService.findById(datasourceId)
                    .map(d -> d.organizationId())
                    .orElseThrow(() -> new DatasourceUnavailableException(messageSource.getMessage(
                            "error.datasource_unavailable_not_found", null,
                            LocaleContextHolder.getLocale())));
            var metadata = new HashMap<String, Object>();
            metadata.put("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName());
            metadata.put("query_type", QueryType.SELECT.name());
            auditLogService.record(new AuditEntry(
                    AuditAction.DATASOURCE_REPLICA_FALLBACK,
                    AuditResourceType.DATASOURCE,
                    datasourceId,
                    organizationId,
                    null,
                    metadata,
                    null,
                    null));
        } catch (RuntimeException auditEx) {
            log.warn("Failed to record DATASOURCE_REPLICA_FALLBACK audit row for {}: {}",
                    datasourceId, auditEx.getMessage());
        }
    }
}
