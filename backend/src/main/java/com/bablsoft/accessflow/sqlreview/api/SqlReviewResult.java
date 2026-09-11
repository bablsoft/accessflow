package com.bablsoft.accessflow.sqlreview.api;

import java.util.List;

/**
 * Outcome of evaluating one query against its resolved ruleset.
 *
 * @param applicable {@code false} when the datasource's engine has no rule support (every
 *                   non-relational plugin engine) — never fails closed into a block
 * @param findings   the violations, in statement order; empty when not applicable
 */
public record SqlReviewResult(boolean applicable, List<SqlReviewFinding> findings) {

    private static final SqlReviewResult NOT_APPLICABLE = new SqlReviewResult(false, List.of());

    public SqlReviewResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** The result for an engine the rule catalog does not cover. */
    public static SqlReviewResult notApplicable() {
        return NOT_APPLICABLE;
    }

    /** An applicable evaluation that found nothing. */
    public static SqlReviewResult clean() {
        return new SqlReviewResult(true, List.of());
    }

    /** Whether any finding carries {@link SqlReviewSeverity#BLOCK}. */
    public boolean hasBlocking() {
        return findings.stream().anyMatch(SqlReviewFinding::isBlocking);
    }
}
