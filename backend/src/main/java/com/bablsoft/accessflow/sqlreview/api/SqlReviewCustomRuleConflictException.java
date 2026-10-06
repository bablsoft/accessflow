package com.bablsoft.accessflow.sqlreview.api;

/**
 * Thrown when a create would give the organization a second custom rule with the same
 * {@code rule_id} (#1010). Mapped to HTTP 409.
 */
public final class SqlReviewCustomRuleConflictException extends RuntimeException {

    private final String ruleId;

    public SqlReviewCustomRuleConflictException(String ruleId) {
        super("A SQL review custom rule with id " + ruleId + " already exists");
        this.ruleId = ruleId;
    }

    public String ruleId() {
        return ruleId;
    }
}
