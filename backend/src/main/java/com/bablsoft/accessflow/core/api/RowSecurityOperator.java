package com.bablsoft.accessflow.core.api;

/**
 * Comparison operator a row-security policy applies between its column and the resolved value.
 * Mirrors the PostgreSQL {@code row_security_operator} enum. {@link #IN} / {@link #NOT_IN} are the
 * multi-valued operators (right-hand side is a list, e.g. the {@code :user.groups} built-in); the
 * rest are scalar.
 */
public enum RowSecurityOperator {
    EQUALS,
    NOT_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    IN,
    NOT_IN,
    /**
     * Unary {@code IS NULL} — the directive's {@code values} are empty and no parameter is bound.
     * Used by lifecycle soft-delete read filtering ({@code <marker> IS NULL}); not persisted by
     * row-security policies, so it never appears in the {@code row_security_operator} PG enum column.
     */
    IS_NULL;

    /** True for the list-valued operators ({@code IN} / {@code NOT_IN}). */
    public boolean isMultiValue() {
        return this == IN || this == NOT_IN;
    }
}
