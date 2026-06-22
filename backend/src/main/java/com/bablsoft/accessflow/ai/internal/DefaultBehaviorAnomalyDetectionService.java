package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyDetectionService;
import com.bablsoft.accessflow.ai.internal.config.AnomalyDetectionProperties;
import com.bablsoft.accessflow.audit.api.BehaviorAuditAggregationService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.events.AnomalyDetectedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates one anomaly-detection pass for the most recent <em>completed</em> window: enumerate
 * active subjects from the audit stream, extract window features, compare against the rolling
 * baseline (before folding the current window in), persist new anomalies (deduped per window),
 * attach a fail-safe AI summary, publish {@link AnomalyDetectedEvent} per anomaly, then fold the
 * window into the baseline. Per-subject failures are swallowed so one bad subject never aborts the
 * batch.
 */
@Service
@RequiredArgsConstructor
class DefaultBehaviorAnomalyDetectionService implements BehaviorAnomalyDetectionService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultBehaviorAnomalyDetectionService.class);

    private final BehaviorAuditAggregationService auditAggregationService;
    private final BehaviorFeatureExtractor featureExtractor;
    private final DefaultBehaviorBaselineService baselineService;
    private final StatisticalAnomalyDetector detector;
    private final DefaultBehaviorAnomalyService anomalyService;
    private final AnomalySummaryService summaryService;
    private final UserQueryService userQueryService;
    private final DatasourceLookupService datasourceLookupService;
    private final ApplicationEventPublisher eventPublisher;
    private final AnomalyDetectionProperties properties;

    @Override
    public int refreshAndDetectAll(Instant now) {
        var window = windowFor(now);
        var subjects = auditAggregationService.findActiveSubjects(window.start(), window.end());
        int total = 0;
        for (var subject : subjects) {
            try {
                total += detectForWindow(subject.organizationId(), subject.userId(),
                        subject.datasourceId(), window);
            } catch (RuntimeException ex) {
                log.error("Anomaly detection failed for org={} user={} datasource={}",
                        subject.organizationId(), subject.userId(), subject.datasourceId(), ex);
            }
        }
        return total;
    }

    @Override
    public int refreshAndDetectFor(UUID organizationId, UUID userId, UUID datasourceId, Instant now) {
        return detectForWindow(organizationId, userId, datasourceId, windowFor(now));
    }

    private int detectForWindow(UUID organizationId, UUID userId, UUID datasourceId, Window window) {
        var baseline = baselineService.load(organizationId, userId, datasourceId);
        if (baselineService.alreadyFolded(baseline, window.start())) {
            return 0; // this window was already processed in an earlier tick
        }
        var samples = auditAggregationService.samplesFor(organizationId, userId, datasourceId,
                window.start(), window.end());
        if (samples.isEmpty()) {
            return 0;
        }
        var features = featureExtractor.extract(samples, window.start(), window.end());
        var detected = detector.detect(baseline.profile(), features, properties);

        int persisted = 0;
        if (!detected.isEmpty()) {
            var userLabel = userLabel(userId);
            var datasourceLabel = datasourceLabel(datasourceId);
            for (var anomaly : detected) {
                if (anomalyService.existsForWindow(organizationId, userId, datasourceId,
                        anomaly.feature(), window.start())) {
                    continue;
                }
                var summary = summaryService
                        .summarize(organizationId, anomaly, userLabel, datasourceLabel)
                        .orElse(null);
                var saved = anomalyService.persist(organizationId, userId, datasourceId, anomaly,
                        window.start(), window.end(), summary);
                if (saved.isPresent()) {
                    var entity = saved.get();
                    eventPublisher.publishEvent(new AnomalyDetectedEvent(entity.getId(),
                            organizationId, userId, datasourceId, anomaly.feature(), anomaly.score()));
                    persisted++;
                }
            }
        }
        baselineService.fold(baseline, features, window.start(), properties.maxBaselineSamples());
        return persisted;
    }

    private String userLabel(UUID userId) {
        return userQueryService.findById(userId)
                .map(DefaultBehaviorAnomalyDetectionService::displayLabel)
                .orElse(userId.toString());
    }

    private static String displayLabel(UserView user) {
        if (user.displayName() != null && !user.displayName().isBlank()) {
            return user.displayName();
        }
        return user.email() != null ? user.email() : user.id().toString();
    }

    private String datasourceLabel(UUID datasourceId) {
        return datasourceLookupService.findRef(datasourceId)
                .map(DatasourceRef::name)
                .orElse(datasourceId.toString());
    }

    /** The most recent completed window aligned to {@code lookbackWindow}. */
    private Window windowFor(Instant now) {
        Duration lookback = properties.lookbackWindow();
        long lookbackMillis = lookback.toMillis();
        long endMillis = Math.floorDiv(now.toEpochMilli(), lookbackMillis) * lookbackMillis;
        Instant end = Instant.ofEpochMilli(endMillis);
        return new Window(end.minus(lookback), end);
    }

    private record Window(Instant start, Instant end) {
    }
}
