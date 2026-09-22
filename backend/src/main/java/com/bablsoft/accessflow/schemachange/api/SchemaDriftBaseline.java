package com.bablsoft.accessflow.schemachange.api;

/**
 * What a drift scan compares an environment's live schema against (#878, epic #870) — maps to the
 * {@code schema_drift_baseline} PG enum. {@code PREVIOUS_ENVIRONMENT} is the adjacent lower
 * environment in the pipeline's ladder; {@code BASELINE_ENVIRONMENT} an admin-designated reference
 * environment; {@code PROMOTION_SNAPSHOT} the schema introspected right after the last
 * {@link SchemaChangePromotionStatus#APPLIED} promotion to the scanned environment — the mode that
 * catches out-of-band changes.
 */
public enum SchemaDriftBaseline {
    PREVIOUS_ENVIRONMENT,
    BASELINE_ENVIRONMENT,
    PROMOTION_SNAPSHOT
}
