package com.bablsoft.accessflow.schemachange.internal;

/**
 * Why a drift scan recorded no findings, or could not finish (#881). These land verbatim in
 * {@code schema_drift_scans.error_message}.
 *
 * <p>Deliberately stable machine-readable codes rather than localized prose: the row is written once
 * by a background job and read afterwards by every locale in the organization, and this module's
 * service layer is locale-free by convention (the web layer localizes). The web UI (#883) renders
 * them; {@code docs/04-api-spec.md} is the public list.
 *
 * <p>A scan that resolved a baseline and compared it records no reason at all — "no findings" and
 * "nothing was compared" must never look alike.
 */
final class SchemaDriftScanReason {

    /** The datasource's engine samples rather than reading a catalog; nothing was introspected. */
    static final String ENGINE_NOT_APPLICABLE = "ENGINE_NOT_APPLICABLE";

    /** No lower rung of the ladder binds a datasource, so there is nothing to compare against. */
    static final String BASELINE_PREVIOUS_ENVIRONMENT_NOT_FOUND = "BASELINE_PREVIOUS_ENVIRONMENT_NOT_FOUND";

    /** The mode is BASELINE_ENVIRONMENT but no reference environment is designated. */
    static final String BASELINE_ENVIRONMENT_NOT_CONFIGURED = "BASELINE_ENVIRONMENT_NOT_CONFIGURED";

    /** The designated reference environment no longer exists, or is not on this pipeline. */
    static final String BASELINE_ENVIRONMENT_NOT_FOUND = "BASELINE_ENVIRONMENT_NOT_FOUND";

    /**
     * The designated reference environment is the one being scanned. The designation is pipeline-wide,
     * so the job reaches this rung on every run; comparing it against itself would be vacuously clean,
     * which is the one outcome this feature must never produce silently.
     */
    static final String BASELINE_ENVIRONMENT_IS_TARGET = "BASELINE_ENVIRONMENT_IS_TARGET";

    /** The baseline environment binds no datasource. */
    static final String BASELINE_ENVIRONMENT_NO_DATASOURCE = "BASELINE_ENVIRONMENT_NO_DATASOURCE";

    /**
     * The baseline and the scanned datasource run different engines. Their type vocabularies differ,
     * so every column would report a type mismatch forever — the flapping failure the applicability
     * rule exists to prevent.
     */
    static final String BASELINE_ENGINE_MISMATCH = "BASELINE_ENGINE_MISMATCH";

    /** No applied promotion to this environment carries a post-apply snapshot yet. */
    static final String BASELINE_SNAPSHOT_MISSING = "BASELINE_SNAPSHOT_MISSING";

    /** The stored snapshot could not be deserialized. */
    static final String BASELINE_SNAPSHOT_UNREADABLE = "BASELINE_SNAPSHOT_UNREADABLE";

    /** The environment has been rebound to a different datasource since the snapshot was taken. */
    static final String BASELINE_DATASOURCE_REBOUND = "BASELINE_DATASOURCE_REBOUND";

    /** The reference database could not be introspected. The scanned side is healthy. */
    static final String BASELINE_INTROSPECTION_FAILED = "BASELINE_INTROSPECTION_FAILED";

    /** The scanned database could not be introspected. The cause is appended after a colon. */
    static final String TARGET_INTROSPECTION_FAILED = "TARGET_INTROSPECTION_FAILED";

    /**
     * The scan failed inside AccessFlow itself — diffing or recording findings — after both databases
     * were read. Kept apart from {@link #TARGET_INTROSPECTION_FAILED} so nobody goes debugging a
     * healthy customer database. The cause is appended after a colon.
     */
    static final String SCAN_FAILED = "SCAN_FAILED";

    /** Foreign keys were not compared because one side reported none at all. */
    static final String FK_COMPARISON_SUPPRESSED = "FK_COMPARISON_SUPPRESSED";

    /** A manual scan lost the cluster race and never ran; the holder's scan is the real one. */
    static final String SCAN_SUPERSEDED = "SCAN_SUPERSEDED";

    private SchemaDriftScanReason() {
    }

    /**
     * Whether a reason means the scan <em>failed</em>, as opposed to a configuration state such as an
     * inapplicable engine or a missing baseline. Only failures surface on the pipeline's
     * {@code last_scan_error}; every reason stays on its own scan row.
     */
    static boolean isFailure(String reason) {
        return reason != null && (reason.startsWith(TARGET_INTROSPECTION_FAILED)
                || reason.startsWith(SCAN_FAILED)
                || reason.equals(BASELINE_INTROSPECTION_FAILED));
    }
}
