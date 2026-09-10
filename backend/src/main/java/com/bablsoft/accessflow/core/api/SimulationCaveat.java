package com.bablsoft.accessflow.core.api;

/**
 * An approximation that applied to a policy simulation (issue AF-630). Replaying past traffic
 * cannot reconstruct every signal as it stood at submission, and a simulator that hides that is
 * worse than one that admits it — so each result names the caveats that were in play and the UI
 * renders them alongside the counts.
 */
public enum SimulationCaveat {

    /**
     * The submitter's role and group memberships were read as they are <em>now</em>, not as of
     * submission. Both arms of the A/B see the same memberships, so the diff is sound; the absolute
     * counts may not be.
     */
    MEMBERSHIP_STATE_CURRENT,

    /**
     * The behavioural-anomaly signal (UBA, AF-383) was read as it is <em>now</em>. Emitted only when
     * a policy under simulation actually reads it.
     */
    ANOMALY_STATE_CURRENT,

    /**
     * Masking was matched on bare column names. Persisted result columns record a name and a JDBC
     * type but no schema or table, so a policy keyed on {@code table.column} may over-report where
     * two tables share a column name.
     */
    COLUMN_MATCH_BARE_NAME,

    /**
     * At least one row could not be classified offline because its engine needs live schema
     * knowledge (Cassandra / ScyllaDB). Those rows are counted separately and are never reported as
     * unaffected.
     */
    ENGINE_CLASSIFICATION_UNAVAILABLE,

    /**
     * The AF-624 pre-flight cost estimate is computed per <em>submitted</em> query, and an access
     * simulation (issue AF-859) has no submitted query — so {@code estimated_rows} and
     * {@code scan_type} conditions evaluate to {@code false} rather than to what they would see in
     * production.
     */
    COST_ESTIMATE_ABSENT,

    /**
     * A hypothetical request carries no source IP, user agent or CI/CD origin (issue AF-859), so
     * every AF-446 client-context condition evaluates to {@code false}. A policy keyed on one of
     * those reports as unmatched here and would still fire on the real submission — which is why
     * this is surfaced rather than smoothed over.
     */
    CLIENT_CONTEXT_ABSENT
}
