package com.bablsoft.accessflow.sqlreview.api;

import java.util.UUID;

/**
 * Thrown when a ruleset does not exist or belongs to another organization — the two are
 * deliberately indistinguishable (#863). Mapped to HTTP 404.
 */
public final class SqlReviewRulesetNotFoundException extends RuntimeException {

    private final UUID rulesetId;

    public SqlReviewRulesetNotFoundException(UUID rulesetId) {
        super("SQL review ruleset not found: " + rulesetId);
        this.rulesetId = rulesetId;
    }

    public UUID rulesetId() {
        return rulesetId;
    }
}
