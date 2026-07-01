package com.bablsoft.accessflow.lifecycle.api;

/**
 * Thrown when an erasure configuration (shared by retention policies and user erasure requests,
 * AF-519) violates a business invariant: a condition with an inconsistent operator/value arity, a
 * blank condition column, an unparseable raw {@code WHERE} clause, an unparseable cron schedule, or
 * conditions/raw-WHERE targeting a non-SQL (NoSQL) datasource where they are not supported. The
 * {@code reason} drives the i18n message key on the {@code ProblemDetail}.
 */
public final class InvalidErasureConfigException extends LifecycleException {

    public enum Reason {
        CONDITION_COLUMN_REQUIRED,
        CONDITION_VALUE_ARITY,
        INVALID_RAW_WHERE,
        INVALID_CRON,
        UNSUPPORTED_DATASOURCE,
        /** An erasure request specified neither a subject, structured conditions, nor a raw WHERE. */
        EMPTY_REQUEST,
        /** Structured conditions / raw WHERE were given without a target table to apply them to. */
        TARGET_TABLE_REQUIRED
    }

    private final transient Reason reason;

    public InvalidErasureConfigException(Reason reason) {
        super("Invalid erasure configuration: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
