package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;

/**
 * Thrown when a create / update would bind a second ruleset to an environment another ruleset in
 * the organization already claims, or would create a second organization-wide default (#863).
 * Mapped to HTTP 409 — a {@code null} {@link #environment()} is the default-ruleset conflict.
 */
public final class SqlReviewRulesetConflictException extends RuntimeException {

    private final DatasourceEnvironment environment;

    public SqlReviewRulesetConflictException(DatasourceEnvironment environment) {
        super(environment == null
                ? "The organization already has a default SQL review ruleset"
                : "A SQL review ruleset is already bound to environment " + environment);
        this.environment = environment;
    }

    /** The contested environment, or {@code null} for the organization-wide default. */
    public DatasourceEnvironment environment() {
        return environment;
    }
}
