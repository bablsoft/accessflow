package com.bablsoft.accessflow.schemachange.internal.scheduled;

import com.bablsoft.accessflow.schemachange.internal.SchemaDriftScanCoordinator;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Drives the scheduled schema drift scans (#881, epic #870). Wakes every
 * {@code accessflow.schemachange.drift-poll-interval} (default 6 hours) and scans every
 * schema-bound environment of each enabled {@code schema_drift_configs} row whose
 * {@code last_scan_at} is older than its own {@code scan_interval_hours} (or was never scanned).
 *
 * <p>It drains a config table rather than enumerating pipelines because {@code deploygov} exposes no
 * cross-organization pipeline listing — and because drift opens connections to customer databases on
 * a timer, which must be opted into rather than inherited by an upgrade.
 *
 * <p>The {@link SchedulerLock} keeps one replica per tick; each environment is additionally scanned
 * under its own cluster lock, so an on-demand "Scan now" elsewhere cannot double up. Per-pipeline
 * and per-environment failures are logged and skipped, so one unreachable database never aborts the
 * batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaDriftJob {

    private final SchemaDriftConfigRepository configRepository;
    private final SchemaDriftScanCoordinator scanCoordinator;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.schemachange.drift-poll-interval:PT6H}")
    @SchedulerLock(name = "schemaDriftJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void run() {
        var now = clock.instant();
        var due = configRepository.findAllByEnabledTrue().stream()
                .filter(config -> isDue(config, now))
                .toList();
        if (due.isEmpty()) {
            log.debug("No schema drift scans due");
            return;
        }
        var scanned = 0;
        for (var config : due) {
            try {
                scanned += scanCoordinator.scanPipeline(config);
            } catch (RuntimeException ex) {
                // A pipeline whose ladder cannot even be read must not abort the batch.
                log.error("Schema drift scan failed for pipeline {}", config.getPipelineId(), ex);
            }
        }
        log.info("Completed {} schema drift environment scans (pipelines due {})", scanned, due.size());
    }

    private boolean isDue(SchemaDriftConfigEntity config, Instant now) {
        if (config.getLastScanAt() == null) {
            return true;
        }
        return !config.getLastScanAt().plus(Duration.ofHours(config.getScanIntervalHours()))
                .isAfter(now);
    }
}
