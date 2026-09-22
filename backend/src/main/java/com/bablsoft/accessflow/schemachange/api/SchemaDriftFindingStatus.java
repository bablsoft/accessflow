package com.bablsoft.accessflow.schemachange.api;

/**
 * Lifecycle of a drift finding (#878, epic #870) — maps to the {@code schema_drift_finding_status}
 * PG enum. {@code OPEN} was seen by the latest scan; {@code ACKNOWLEDGED} an admin accepted it;
 * {@code RESOLVED} a later scan no longer observed it. Drift never writes to the customer database.
 */
public enum SchemaDriftFindingStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
