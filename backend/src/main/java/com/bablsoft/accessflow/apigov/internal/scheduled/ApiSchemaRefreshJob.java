package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionService;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRefreshCandidate;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Re-fetches {@code sourceUrl} schemas that opted into a refresh interval and records drift (#893).
 * Writes revisions and findings only — the governed catalog changes solely on an admin accept. The
 * poll interval is just the tick; each schema's own {@code refresh_interval_minutes} decides when it
 * is due.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiSchemaRefreshJob {

    private final ApiSchemaRepository schemaRepository;
    private final ApiSchemaRevisionService revisionService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.apigov.schema-refresh-poll-interval:PT15M}")
    @SchedulerLock(name = "apiSchemaRefreshJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        var now = clock.instant();
        var due = schemaRepository.findRefreshCandidates().stream()
                .filter(candidate -> isDue(candidate, now))
                .toList();
        var drifted = 0;
        for (var candidate : due) {
            try {
                var result = revisionService.refresh(candidate.connectorId(), candidate.organizationId(),
                        candidate.schemaId());
                if (result.findingCount() > 0) {
                    drifted++;
                }
            } catch (ApiSchemaFetchException | ApiSchemaParseException ex) {
                // Already recorded on the schema's last_refresh_error; an upstream outage is not drift.
                log.warn("Schema refresh failed for API schema {}: {}", candidate.schemaId(), ex.getMessage());
            } catch (RuntimeException ex) {
                // One bad row must not abort the batch; the next tick retries it.
                log.error("Schema refresh errored for API schema {}", candidate.schemaId(), ex);
            }
        }
        if (!due.isEmpty()) {
            log.info("API schema refresh pass: refreshed {}, drifted {}", due.size(), drifted);
        }
    }

    private static boolean isDue(ApiSchemaRefreshCandidate candidate, Instant now) {
        if (candidate.lastRefreshAt() == null) {
            return true;
        }
        return !candidate.lastRefreshAt().plus(Duration.ofMinutes(candidate.refreshIntervalMinutes()))
                .isAfter(now);
    }
}
