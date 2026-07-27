package com.bablsoft.accessflow.discovery.internal.scheduled;

import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
import com.bablsoft.accessflow.discovery.internal.DiscoveryScanService;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryScanConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Drives the scheduled sensitive-data discovery scans (AF-623). Wakes every
 * {@code accessflow.discovery.scan-poll-interval} (default 15 minutes) and runs a scan for every
 * enabled {@code discovery_scan_config} whose {@code last_scan_at} is older than its own
 * {@code scan_interval_hours} (or was never scanned).
 *
 * <p>The {@link SchedulerLock} guarantees only one node in a cluster runs per tick (Redis-backed
 * {@code LockProvider} in the {@code scheduling} module). Per-datasource failures are logged and
 * skipped so one bad datasource never aborts the batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DiscoveryScanJob {

    private final DiscoveryScanConfigRepository configRepository;
    private final DiscoveryScanService scanService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.discovery.scan-poll-interval:PT15M}")
    @SchedulerLock(name = "discoveryScanJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        var now = clock.instant();
        var due = configRepository.findAllByEnabledTrue().stream()
                .filter(config -> isDue(config, now))
                .toList();
        if (due.isEmpty()) {
            log.debug("No discovery scans due");
            return;
        }
        var scanned = 0;
        for (var config : due) {
            try {
                scanService.scan(config.getDatasourceId(), config.getOrganizationId(), null);
                scanned++;
            } catch (DiscoveryScanAlreadyRunningException ex) {
                log.info("Skipping discovery scan for datasource {} — already running",
                        config.getDatasourceId());
            } catch (RuntimeException ex) {
                log.error("Discovery scan failed for datasource {}", config.getDatasourceId(), ex);
            }
        }
        log.info("Completed {} discovery scans (due {})", scanned, due.size());
    }

    private boolean isDue(DiscoveryScanConfigEntity config, Instant now) {
        if (config.getLastScanAt() == null) {
            return true;
        }
        return !config.getLastScanAt().plus(Duration.ofHours(config.getScanIntervalHours()))
                .isAfter(now);
    }
}
