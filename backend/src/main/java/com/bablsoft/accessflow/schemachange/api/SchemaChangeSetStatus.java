package com.bablsoft.accessflow.schemachange.api;

/**
 * Lifecycle of an authored change set (#878, epic #870) — maps to the
 * {@code schema_change_set_status} PG enum. {@code DRAFT} is being authored, {@code ACTIVE} has
 * been promoted at least once, {@code ARCHIVED} is retired and can no longer be promoted.
 */
public enum SchemaChangeSetStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
