package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.proxy.api.ReplicaEndpointRef;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Picks the right HikariCP pool for a query. {@link QueryType#SELECT} load-balances round-robin
 * across the datasource's healthy read-replica endpoints (AF-457): endpoints with an open circuit
 * in {@link ReplicaHealthRegistry} are skipped, a connection failure marks the endpoint down and
 * moves on to the next, and only when every endpoint is down or failed does the read fall back to
 * the primary. All non-SELECT statements (DML/DDL and transactional batches) always hit the
 * primary.
 *
 * <p>Replica fall-back emits one {@link AuditAction#DATASOURCE_REPLICA_FALLBACK} audit row per
 * <em>replica-set exhaustion with at least one live failure</em> — not per endpoint, and not for
 * SELECTs served by the primary while every circuit is already open (those were audited when they
 * failed). The underlying failure never propagates to the caller — the query still runs against
 * the primary.
 */
@Component
@RequiredArgsConstructor
class RoutingDataSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(RoutingDataSourceResolver.class);

    private final DatasourceConnectionPoolManager poolManager;
    private final ReplicaHealthRegistry healthRegistry;
    private final DatasourceLookupService datasourceLookupService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final ObservationRegistry observationRegistry;

    private final ConcurrentMap<UUID, AtomicInteger> roundRobinOffsets = new ConcurrentHashMap<>();

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
            var replica = tryReplicas(datasourceId);
            if (replica != null) {
                return replica;
            }
        }
        return poolManager.resolve(datasourceId).getConnection();
    }

    /**
     * Round-robin over the healthy replica endpoints; {@code null} when the datasource has no
     * replicas or none could serve the read (the caller then uses the primary).
     */
    private Connection tryReplicas(UUID datasourceId) {
        List<ReplicaEndpointRef> endpoints = poolManager.replicaEndpoints(datasourceId);
        if (endpoints.isEmpty()) {
            return null;
        }
        int size = endpoints.size();
        int start = Math.floorMod(
                roundRobinOffsets.computeIfAbsent(datasourceId, id -> new AtomicInteger())
                        .getAndIncrement(),
                size);
        var triedLabels = new ArrayList<String>();
        Throwable lastFailure = null;
        for (int i = 0; i < size; i++) {
            var endpoint = endpoints.get((start + i) % size);
            if (!healthRegistry.isCandidate(datasourceId, endpoint.endpointId())) {
                continue;
            }
            triedLabels.add(endpoint.label());
            try {
                Connection connection = poolManager
                        .resolveReplica(datasourceId, endpoint.endpointId())
                        .getConnection();
                healthRegistry.recordSuccess(datasourceId, endpoint.endpointId());
                return connection;
            } catch (SQLException | RuntimeException ex) {
                healthRegistry.recordFailure(datasourceId, endpoint.endpointId());
                log.warn("Read-replica {} failed for datasource {}; trying next candidate: {}",
                        endpoint.label(), datasourceId, ex.getMessage());
                lastFailure = ex;
            }
        }
        if (lastFailure != null) {
            recordFallback(datasourceId, lastFailure, size, triedLabels);
        } else {
            log.debug("All {} replica endpoints of datasource {} have open circuits; "
                    + "serving SELECT from primary", size, datasourceId);
        }
        return null;
    }

    private void recordFallback(UUID datasourceId, Throwable ex, int replicaCount,
                                List<String> triedLabels) {
        log.warn("All read-replica endpoints failed for datasource {}; falling back to primary: {}",
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
            metadata.put("replica_count", replicaCount);
            metadata.put("tried_endpoints", triedLabels);
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
