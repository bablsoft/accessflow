package com.bablsoft.accessflow.proxy.internal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.concurrent.ScheduledFuture;

/**
 * Per-node async health prober for read-replica endpoints (AF-457). Every
 * {@code accessflow.proxy.replica.probe-interval} it runs {@code Connection.isValid(...)} against
 * each replica pool <em>already cached on this node</em> (probing never creates pools) and feeds
 * the result into {@link ReplicaHealthRegistry} — so a recovered replica re-enters the read
 * rotation without waiting for a half-open trial, and a silently-degraded one is taken out before
 * a user query has to fail on it.
 *
 * <p>Deliberately NOT a {@code @Scheduled}/{@code @SchedulerLock} job: ShedLock makes a job a
 * cluster singleton, but HikariCP pools and the breaker state are per JVM — every node must probe
 * its own pools. The Boot-autoconfigured {@link TaskScheduler} runs the probe on virtual threads
 * ({@code spring.threads.virtual.enabled=true}), so no platform thread is tied up.
 */
@Component
class ReplicaHealthProber {

    private static final Logger log = LoggerFactory.getLogger(ReplicaHealthProber.class);

    private final DefaultDatasourceConnectionPoolManager poolManager;
    private final ReplicaHealthRegistry healthRegistry;
    private final ProxyReplicaProperties properties;
    private final TaskScheduler taskScheduler;

    private volatile ScheduledFuture<?> scheduled;

    ReplicaHealthProber(DefaultDatasourceConnectionPoolManager poolManager,
                        ReplicaHealthRegistry healthRegistry,
                        ProxyReplicaProperties properties,
                        TaskScheduler taskScheduler) {
        this.poolManager = poolManager;
        this.healthRegistry = healthRegistry;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    void start() {
        scheduled = taskScheduler.scheduleWithFixedDelay(this::probeAll,
                properties.probeInterval());
    }

    @PreDestroy
    void stop() {
        var current = scheduled;
        if (current != null) {
            current.cancel(false);
        }
    }

    void probeAll() {
        poolManager.forEachCachedReplicaPool((datasourceId, endpointId, pool) -> {
            try (var connection = pool.getConnection()) {
                if (connection.isValid((int) properties.probeTimeout().toSeconds())) {
                    healthRegistry.recordSuccess(datasourceId, endpointId);
                } else {
                    markDown(datasourceId, endpointId, "isValid returned false");
                }
            } catch (SQLException | RuntimeException ex) {
                markDown(datasourceId, endpointId,
                        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName());
            }
        });
    }

    private void markDown(java.util.UUID datasourceId, java.util.UUID endpointId, String reason) {
        log.warn("Replica health probe failed for datasource {} endpoint {}: {}",
                datasourceId, endpointId, reason);
        healthRegistry.recordFailure(datasourceId, endpointId);
    }
}
