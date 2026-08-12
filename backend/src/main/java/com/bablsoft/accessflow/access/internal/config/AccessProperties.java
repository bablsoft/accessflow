package com.bablsoft.accessflow.access.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the access (JIT) module.
 *
 * <ul>
 *   <li>{@code grantExpiryPollInterval} — cadence of {@code AccessGrantExpiryJob} (also wired via
 *       the {@code @Scheduled} fixedDelayString fallback so the job has a default at startup).</li>
 *   <li>{@code minDuration}/{@code maxDuration} — inclusive bounds the requested access duration
 *       must fall within; enforced in {@code DefaultAccessRequestService}.</li>
 *   <li>{@code usage} — least-privilege intelligence (#625); see {@link Usage}.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.access")
public record AccessProperties(
        Duration grantExpiryPollInterval,
        Duration minDuration,
        Duration maxDuration,
        Usage usage) {

    public AccessProperties {
        if (grantExpiryPollInterval == null) {
            grantExpiryPollInterval = Duration.ofMinutes(5);
        }
        if (minDuration == null) {
            minDuration = Duration.ofMinutes(15);
        }
        if (maxDuration == null) {
            maxDuration = Duration.ofDays(30);
        }
        if (usage == null) {
            usage = new Usage(null, null, null, null, 0, 0, 0, 0, 0, null, null);
        }
    }

    /**
     * Grant-usage analytics tunables ({@code accessflow.access.usage.*}, #625).
     *
     * <ul>
     *   <li>{@code aggregationPollInterval} — cadence of {@code GrantUsageAggregationJob} (also the
     *       {@code @Scheduled} fixedDelayString fallback so the job has a default at startup).</li>
     *   <li>{@code backfillWindow} — how far back a newly-summarised grant starts observing. Bounds
     *       the one-time backfill read, and caps how far into the past "never used" can see.</li>
     *   <li>{@code stalenessThreshold} — a grant unused for longer than this is {@code STALE}.</li>
     *   <li>{@code minObservationWindow} — below this the verdict is {@code INSUFFICIENT_DATA}
     *       rather than {@code NEVER_USED}, so a fresh grant is never recommended for revocation.</li>
     *   <li>{@code overScopedThreshold} — exercised/granted target ratio below which an actively-used
     *       grant is flagged {@code OVER_SCOPED}. Clamped to (0, 1].</li>
     *   <li>{@code maxRowsPerTick} — audit events read per keyset page.</li>
     *   <li>{@code maxPagesPerTick} — pages read per organization per tick. The read drains until a
     *       short page arrives; this bounds the work a single very busy tenant can do in one tick.
     *       Hitting it is logged and the cursor stops at the last applied event, so the remainder is
     *       picked up next tick rather than skipped.</li>
     *   <li>{@code maxTrackedTargets} — cap on the distinct exercised targets retained per grant.</li>
     *   <li>{@code maxReportRows} — hard cap on rows in a single over-provisioned CSV export; beyond
     *       it the export is flagged truncated.</li>
     *   <li>{@code nudgeEnabled} — master switch for the staleness nudge notification.</li>
     *   <li>{@code nudgeCooldown} — minimum time between nudges for the same grant.</li>
     * </ul>
     */
    public record Usage(
            Duration aggregationPollInterval,
            Duration backfillWindow,
            Duration stalenessThreshold,
            Duration minObservationWindow,
            double overScopedThreshold,
            int maxRowsPerTick,
            int maxPagesPerTick,
            int maxTrackedTargets,
            int maxReportRows,
            Boolean nudgeEnabled,
            Duration nudgeCooldown) {

        public Usage {
            if (aggregationPollInterval == null || aggregationPollInterval.isZero()
                    || aggregationPollInterval.isNegative()) {
                aggregationPollInterval = Duration.ofHours(1);
            }
            if (backfillWindow == null || backfillWindow.isZero() || backfillWindow.isNegative()) {
                backfillWindow = Duration.ofDays(90);
            }
            if (stalenessThreshold == null || stalenessThreshold.isZero()
                    || stalenessThreshold.isNegative()) {
                stalenessThreshold = Duration.ofDays(60);
            }
            if (minObservationWindow == null || minObservationWindow.isNegative()) {
                minObservationWindow = Duration.ofDays(14);
            }
            if (overScopedThreshold <= 0 || overScopedThreshold > 1) {
                overScopedThreshold = 0.5;
            }
            if (maxRowsPerTick <= 0) {
                maxRowsPerTick = 50_000;
            }
            if (maxPagesPerTick <= 0) {
                maxPagesPerTick = 20;
            }
            if (maxTrackedTargets <= 0) {
                maxTrackedTargets = 200;
            }
            if (maxReportRows <= 0) {
                maxReportRows = 50_000;
            }
            if (nudgeEnabled == null) {
                nudgeEnabled = Boolean.TRUE;
            }
            if (nudgeCooldown == null || nudgeCooldown.isNegative()) {
                nudgeCooldown = Duration.ofDays(30);
            }
        }
    }
}
