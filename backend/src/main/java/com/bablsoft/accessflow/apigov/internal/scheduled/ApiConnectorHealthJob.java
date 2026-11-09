package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthService;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorHealthCandidate;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorHealthCheckRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Probes active connectors that opted into scheduled health (#894) and bounds the probe history.
 * The poll interval is just the tick; each connector's own {@code health_check_interval_minutes}
 * decides when it is due. Only base URLs an admin already stored are probed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiConnectorHealthJob {

    private final ApiConnectorRepository connectorRepository;
    private final ApiConnectorHealthCheckRepository healthCheckRepository;
    private final ApiConnectorHealthService healthService;
    private final ApigovRequestProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.apigov.health-poll-interval:PT1M}")
    @SchedulerLock(name = "apiConnectorHealthJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        var now = clock.instant();
        var due = connectorRepository.findHealthCandidates().stream()
                .filter(candidate -> isDue(candidate, now))
                .toList();
        for (var candidate : due) {
            try {
                healthService.probe(candidate.connectorId());
            } catch (RuntimeException ex) {
                // One bad row must not abort the batch; the next tick retries it.
                log.error("Health probe errored for API connector {}", candidate.connectorId(), ex);
            }
        }
        if (!due.isEmpty()) {
            log.info("API connector health pass: probed {}", due.size());
        }
        try {
            var deleted = healthCheckRepository.deleteByCheckedAtBefore(now.minus(properties.healthHistoryRetention()));
            if (deleted > 0) {
                log.debug("Pruned {} API connector health check rows", deleted);
            }
        } catch (RuntimeException ex) {
            log.error("Pruning API connector health history failed", ex);
        }
    }

    private static boolean isDue(ApiConnectorHealthCandidate candidate, Instant now) {
        if (candidate.lastProbeAt() == null) {
            return true;
        }
        return !candidate.lastProbeAt().plus(Duration.ofMinutes(candidate.healthCheckIntervalMinutes()))
                .isAfter(now);
    }
}
