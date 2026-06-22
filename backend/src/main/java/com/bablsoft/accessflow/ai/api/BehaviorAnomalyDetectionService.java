package com.bablsoft.accessflow.ai.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Drives behavioural anomaly detection (UBA, AF-383). Invoked by the scheduled
 * {@code BehaviorAnomalyDetectionJob}; exposed as a public {@code api} interface because the
 * scheduled-job rule forbids a job calling into another package's internals. Implementations refresh
 * rolling baselines from {@code audit_log} metadata and persist deviations. They never propagate a
 * data-level failure for one subject — a bad subject is logged and skipped so the batch continues.
 */
public interface BehaviorAnomalyDetectionService {

    /**
     * Discover every (org, user, datasource) subject active in the most recent detection window
     * (relative to {@code now}) from the audit stream, refresh each baseline, and detect anomalies.
     *
     * @return the number of anomalies persisted across all subjects.
     */
    int refreshAndDetectAll(Instant now);

    /**
     * Refresh the baseline and detect anomalies for a single subject. Used by tests and by the
     * per-subject loop of {@link #refreshAndDetectAll}.
     *
     * @return the number of anomalies persisted for this subject.
     */
    int refreshAndDetectFor(UUID organizationId, UUID userId, UUID datasourceId, Instant now);
}
