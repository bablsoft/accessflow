package com.bablsoft.accessflow.sqlreview.api;

import java.util.UUID;

/**
 * Thrown when a custom rule does not exist or belongs to another organization — the two are
 * deliberately indistinguishable (#1010). Mapped to HTTP 404.
 */
public final class SqlReviewCustomRuleNotFoundException extends RuntimeException {

    private final UUID id;

    public SqlReviewCustomRuleNotFoundException(UUID id) {
        super("SQL review custom rule not found: " + id);
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
