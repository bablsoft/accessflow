package com.bablsoft.accessflow.core.api;

/**
 * Where a row-security policy's comparison value comes from. Mirrors the PostgreSQL
 * {@code row_security_value_type} enum.
 */
public enum RowSecurityValueType {
    /** A {@code user.<key>} reference resolved per submitter (built-in or {@code users.attributes}). */
    VARIABLE,
    /** A fixed literal compared as-is. */
    LITERAL
}
