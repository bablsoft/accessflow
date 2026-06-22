package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for behavioural anomaly detection (UBA, AF-383), bound from {@code accessflow.ai.anomaly.*}.
 * The job cadence itself is read directly by {@code @Scheduled} from
 * {@code accessflow.ai.anomaly.detection-poll-interval}; the rest drive the windowing and the
 * statistical detector.
 *
 * <ul>
 *   <li>{@code lookbackWindow} — size of one detection window (the job evaluates the most recent
 *       <em>completed</em> window of this length); default {@code PT1H}.</li>
 *   <li>{@code zScoreThreshold} — scalar features flag when {@code |z| >= threshold}; default 3.0.</li>
 *   <li>{@code iqrMultiplier} — IQR fence multiplier used when the baseline stddev is ~0; default 1.5.</li>
 *   <li>{@code minSampleSize} — windows folded into the baseline before detection activates (cold-start
 *       guard); default 7.</li>
 *   <li>{@code maxBaselineSamples} — cap on the rolling per-feature observation list; default 90.</li>
 *   <li>{@code offHoursThreshold} — an active hour whose baseline frequency is at or below this is
 *       flagged off-hours; default 0.02.</li>
 *   <li>{@code summaryEnabled} — generate a fail-safe AI natural-language explanation per anomaly;
 *       default {@code true}.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.ai.anomaly")
public record AnomalyDetectionProperties(
        Duration lookbackWindow,
        double zScoreThreshold,
        double iqrMultiplier,
        int minSampleSize,
        int maxBaselineSamples,
        double offHoursThreshold,
        Boolean summaryEnabled) {

    public AnomalyDetectionProperties {
        if (lookbackWindow == null || lookbackWindow.isZero() || lookbackWindow.isNegative()) {
            lookbackWindow = Duration.ofHours(1);
        }
        if (zScoreThreshold <= 0) {
            zScoreThreshold = 3.0;
        }
        if (iqrMultiplier <= 0) {
            iqrMultiplier = 1.5;
        }
        if (minSampleSize <= 0) {
            minSampleSize = 7;
        }
        if (maxBaselineSamples <= 0) {
            maxBaselineSamples = 90;
        }
        if (offHoursThreshold < 0) {
            offHoursThreshold = 0.02;
        }
        if (summaryEnabled == null) {
            summaryEnabled = true;
        }
    }
}
