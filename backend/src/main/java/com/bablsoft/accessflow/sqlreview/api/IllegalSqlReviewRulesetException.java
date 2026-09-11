package com.bablsoft.accessflow.sqlreview.api;

/**
 * Thrown when a ruleset is malformed — a rule id the catalog does not know, a parameterised rule
 * saved without its required list, a malformed glob, or undecodable stored params (#862). The
 * message is resolved in the caller's locale at the throw site. Mapped to HTTP 422 by the
 * ruleset administration surface (#863).
 */
public final class IllegalSqlReviewRulesetException extends RuntimeException {

    public IllegalSqlReviewRulesetException(String message) {
        super(message);
    }

    public IllegalSqlReviewRulesetException(String message, Throwable cause) {
        super(message, cause);
    }
}
