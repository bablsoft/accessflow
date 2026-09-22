package com.bablsoft.accessflow.schemachange.api;

/**
 * How one object path differs between the baseline and the scanned environment (#878, epic #870)
 * — maps to the {@code schema_drift_finding_kind} PG enum.
 */
public enum SchemaDriftFindingKind {
    MISSING_IN_TARGET,
    UNEXPECTED_IN_TARGET,
    TYPE_MISMATCH,
    NULLABILITY_MISMATCH,
    PRIMARY_KEY_MISMATCH,
    FOREIGN_KEY_MISMATCH
}
