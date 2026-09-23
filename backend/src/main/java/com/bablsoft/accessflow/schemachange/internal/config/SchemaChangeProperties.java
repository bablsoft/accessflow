package com.bablsoft.accessflow.schemachange.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for schema change governance (#879, #881; epic #870), bound from
 * {@code accessflow.schemachange.*}.
 *
 * <p>One constructor only. A second convenience constructor silently unbinds every property.
 *
 * @param maxStatements           hard cap on statements per change set. A real cost control, not a
 *                                style rule: promotion analyses every statement as a request-group
 *                                member, each re-introspecting the datasource, so an uncapped set
 *                                multiplies LLM calls and customer-database round-trips by statement
 *                                count and by environment count. {@code null} or non-positive falls
 *                                back to 50.
 * @param driftPollInterval       cadence of {@code SchemaDriftJob}. Read by the {@code @Scheduled}
 *                                placeholder, not by code; declared here for documentation, the
 *                                {@code DiscoveryProperties.scanPollInterval} precedent.
 * @param driftScanTimeBudget     wall-clock budget for one environment's diff loop. Tables past the
 *                                deadline are skipped and the scan is flagged partial — and, because
 *                                a skipped table resolves nothing, a scan that runs out of time is
 *                                incomplete rather than wrong.
 * @param driftMaxTablesPerScan   hard cap on tables compared in one scan, applied to the union of
 *                                both sides; beyond it the scan is flagged partial.
 * @param driftMaxFindingsPerScan hard cap on findings recorded by one scan. Bounds the damage a
 *                                genuinely divergent pair of databases can do to the worklist.
 * @param driftScanLockAtMostFor  how long the cluster-wide {@code schemaDriftScan:<environmentId>}
 *                                lock may be held. A crash ceiling, not a timeout — the lock is
 *                                released as soon as the scan ends, so this only bounds how long a
 *                                dead replica keeps an environment locked out. Clamped to at least
 *                                {@code 2 × driftScanTimeBudget + 10m}: a lock that lapses under a
 *                                still-running scan admits the second scanner it exists to keep out,
 *                                and the additive term covers the two introspections and the
 *                                reconciliation, which all sit outside the loop the budget bounds.
 */
@ConfigurationProperties("accessflow.schemachange")
public record SchemaChangeProperties(Integer maxStatements, Duration driftPollInterval,
                                     Duration driftScanTimeBudget, Integer driftMaxTablesPerScan,
                                     Integer driftMaxFindingsPerScan, Duration driftScanLockAtMostFor) {

    public static final int DEFAULT_MAX_STATEMENTS = 50;

    public SchemaChangeProperties {
        maxStatements = maxStatements == null || maxStatements <= 0 ? DEFAULT_MAX_STATEMENTS : maxStatements;
        if (driftPollInterval == null || driftPollInterval.isNegative() || driftPollInterval.isZero()) {
            driftPollInterval = Duration.ofHours(6);
        }
        if (driftScanTimeBudget == null || driftScanTimeBudget.isNegative() || driftScanTimeBudget.isZero()) {
            driftScanTimeBudget = Duration.ofMinutes(5);
        }
        if (driftMaxTablesPerScan == null || driftMaxTablesPerScan <= 0) {
            driftMaxTablesPerScan = 500;
        }
        if (driftMaxFindingsPerScan == null || driftMaxFindingsPerScan <= 0) {
            driftMaxFindingsPerScan = 500;
        }
        // Last, so driftScanTimeBudget is already defaulted when the floor below reads it.
        if (driftScanLockAtMostFor == null) {
            driftScanLockAtMostFor = Duration.ofMinutes(30);
        }
        var floor = driftScanTimeBudget.multipliedBy(2).plus(Duration.ofMinutes(10));
        if (driftScanLockAtMostFor.compareTo(floor) < 0) {
            driftScanLockAtMostFor = floor;
        }
    }
}
