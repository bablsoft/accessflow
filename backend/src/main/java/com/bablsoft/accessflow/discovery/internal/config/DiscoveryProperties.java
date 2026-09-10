package com.bablsoft.accessflow.discovery.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the sensitive-data discovery module (AF-623).
 *
 * <ul>
 *   <li>{@code scanPollInterval} — cadence of {@code DiscoveryScanJob}. Read directly by the
 *       {@code @Scheduled} placeholder, listed here for documentation only.</li>
 *   <li>{@code scanTimeBudget} — wall-clock budget for a single datasource scan; tables past the
 *       deadline are skipped and the run is flagged partial.</li>
 *   <li>{@code sampleStatementTimeout} — per-table statement timeout for the bounded sample read
 *       (tighter than the general execution timeout, same idea as the AF-624 estimate bound).</li>
 *   <li>{@code maxTablesPerScan} — hard cap on tables sampled in one scan.</li>
 *   <li>{@code maxAiTablesPerScan} — hard cap on tables sent through the optional AI pass,
 *       bounding provider spend.</li>
 *   <li>{@code maxNestedDepth} — how far the scan walks into a nested document/map cell when
 *       flattening it to dot-path pseudo-columns (AF-658).</li>
 *   <li>{@code maxNestedLeavesPerRow} — per-row allowance of nested nodes visited during that
 *       walk; charged for every node touched, so a pathological document cannot turn a bounded
 *       sample into an unbounded scan. Top-level scalar cells are free.</li>
 *   <li>{@code staleScansBeforeExpiry} — how many consecutive scans may sample a finding's table
 *       without re-proposing the column before the finding leaves the active worklist as
 *       {@code STALE} (AF-659). Re-detection resets the counter. The default is deliberately not
 *       1: the sample is {@code SELECT *} with a row cap and no {@code ORDER BY}, so which rows
 *       it sees shifts between runs, and a column sitting near the 30 % match ratio can flap on
 *       unchanged data. Three consecutive misses make an unlucky sample a non-event.</li>
 *   <li>{@code scanLockAtMostFor} — how long the cluster-wide {@code discoveryScan:<datasourceId>}
 *       lock may be held (AF-660). It is the crash ceiling, not a timeout: the lock is released as
 *       soon as the scan ends, and this only bounds how long a dead node can keep a datasource
 *       locked out. Clamped to at least {@code 2 × scanTimeBudget + 10m}, because a lock that
 *       lapses under a still-running scan admits the second scanner it exists to keep out. Note
 *       {@code scanTimeBudget} bounds the table loop, not the whole run — schema introspection
 *       precedes it, and the last table's sample, AI call and the stale sweep all follow it — so
 *       the additive term, not the multiple, is what covers that tail. It is a generous floor, not
 *       a proof: a datasource whose introspection alone runs for hours can still outlive its
 *       lock.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.discovery")
public record DiscoveryProperties(Duration scanPollInterval, Duration scanTimeBudget,
                                  Duration sampleStatementTimeout, Integer maxTablesPerScan,
                                  Integer maxAiTablesPerScan, Integer maxNestedDepth,
                                  Integer maxNestedLeavesPerRow,
                                  Integer staleScansBeforeExpiry, Duration scanLockAtMostFor) {

    public DiscoveryProperties {
        if (scanPollInterval == null) {
            scanPollInterval = Duration.ofMinutes(15);
        }
        if (scanTimeBudget == null || scanTimeBudget.isNegative() || scanTimeBudget.isZero()) {
            scanTimeBudget = Duration.ofMinutes(10);
        }
        if (sampleStatementTimeout == null || sampleStatementTimeout.isNegative()
                || sampleStatementTimeout.isZero()) {
            sampleStatementTimeout = Duration.ofSeconds(10);
        }
        if (maxTablesPerScan == null || maxTablesPerScan <= 0) {
            maxTablesPerScan = 200;
        }
        if (maxAiTablesPerScan == null || maxAiTablesPerScan < 0) {
            maxAiTablesPerScan = 25;
        }
        if (maxNestedDepth == null || maxNestedDepth <= 0) {
            maxNestedDepth = 5;
        }
        if (maxNestedLeavesPerRow == null || maxNestedLeavesPerRow <= 0) {
            maxNestedLeavesPerRow = 100;
        }
        if (staleScansBeforeExpiry == null || staleScansBeforeExpiry <= 0) {
            staleScansBeforeExpiry = 3;
        }
        // Last, so scanTimeBudget above is already defaulted when the floor below reads it.
        if (scanLockAtMostFor == null) {
            scanLockAtMostFor = Duration.ofMinutes(30);
        }
        // The additive term is what covers the work outside scanTimeBudget (introspection before
        // the loop, the overrunning last table, the AI call, the stale sweep) — it does not
        // shrink as the budget does, which a bare multiple would.
        var floor = scanTimeBudget.multipliedBy(2).plus(Duration.ofMinutes(10));
        if (scanLockAtMostFor.compareTo(floor) < 0) {
            scanLockAtMostFor = floor;
        }
    }
}
