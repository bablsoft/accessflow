package com.bablsoft.accessflow.workflow.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for automatic query suggestions (#776), bound from
 * {@code accessflow.workflow.query-suggestions.*}. They bound the aggregation pass over each
 * organisation's approved query history and weight the ranking heuristic.
 *
 * <ul>
 *   <li>{@code enabled} — master switch; when off the job no-ops and reads return an empty list.
 *       Advisory and cheap, hence on by default; default {@code true}.</li>
 *   <li>{@code aggregationPollInterval} — cadence of the scheduled rebuild; default {@code PT6H}.</li>
 *   <li>{@code lookback} — how far back the approved corpus reaches; default {@code P90D}.</li>
 *   <li>{@code maxCorpusRowsPerDatasource} — rows read per datasource in one pass; default 2000.
 *       Per datasource, not per organisation: an org-wide cap would be spent almost entirely on
 *       the busiest datasource.</li>
 *   <li>{@code maxSuggestionsPerDatasource} — rows retained per datasource; default 50.</li>
 *   <li>{@code maxTrackedSubmitters} — submitter ids recorded per suggestion, which bounds the
 *       array width; default 50.</li>
 *   <li>{@code minApprovedCount} — approvals a shape needs before it is offered at all, so a
 *       one-off query cannot fill the rail; default 2.</li>
 *   <li>{@code maxSqlLength} — candidates longer than this are skipped; default 4000.</li>
 *   <li>{@code recencyHalfLife} — the age at which the recency term halves; default {@code P14D}.</li>
 *   <li>{@code weightFrequency} / {@code weightRecency} / {@code weightTableOverlap} — score
 *       weights; defaults 1.0 / 1.0 / 0.5.</li>
 *   <li>{@code defaultLimit} / {@code maxLimit} — the rail's page size; defaults 10 and 50.</li>
 *   <li>{@code recomputeLockAtMostFor} — lock held by an on-demand recompute; default {@code PT10M}.</li>
 * </ul>
 *
 * <p>A single compact constructor and no convenience overload: a second constructor on a
 * {@code @ConfigurationProperties} record silently unbinds every property.
 */
@ConfigurationProperties("accessflow.workflow.query-suggestions")
public record QuerySuggestionProperties(
        Boolean enabled,
        Duration aggregationPollInterval,
        Duration lookback,
        int maxCorpusRowsPerDatasource,
        int maxSuggestionsPerDatasource,
        int maxTrackedSubmitters,
        int minApprovedCount,
        int maxSqlLength,
        Duration recencyHalfLife,
        double weightFrequency,
        double weightRecency,
        double weightTableOverlap,
        int defaultLimit,
        int maxLimit,
        Duration recomputeLockAtMostFor) {

    public QuerySuggestionProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (isUnusable(aggregationPollInterval)) {
            aggregationPollInterval = Duration.ofHours(6);
        }
        if (isUnusable(lookback)) {
            lookback = Duration.ofDays(90);
        }
        if (maxCorpusRowsPerDatasource <= 0) {
            maxCorpusRowsPerDatasource = 2000;
        }
        if (maxSuggestionsPerDatasource <= 0) {
            maxSuggestionsPerDatasource = 50;
        }
        if (maxTrackedSubmitters <= 0) {
            maxTrackedSubmitters = 50;
        }
        if (minApprovedCount <= 0) {
            minApprovedCount = 2;
        }
        if (maxSqlLength <= 0) {
            maxSqlLength = 4000;
        }
        if (isUnusable(recencyHalfLife)) {
            recencyHalfLife = Duration.ofDays(14);
        }
        if (weightFrequency < 0) {
            weightFrequency = 1.0;
        }
        if (weightRecency < 0) {
            weightRecency = 1.0;
        }
        if (weightTableOverlap < 0) {
            weightTableOverlap = 0.5;
        }
        if (defaultLimit <= 0) {
            defaultLimit = 10;
        }
        if (maxLimit <= 0) {
            maxLimit = 50;
        }
        if (defaultLimit > maxLimit) {
            defaultLimit = maxLimit;
        }
        if (isUnusable(recomputeLockAtMostFor)) {
            recomputeLockAtMostFor = Duration.ofMinutes(10);
        }
    }

    private static boolean isUnusable(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }
}
