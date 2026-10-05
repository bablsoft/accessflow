package com.bablsoft.accessflow.sqlreview.api;

/**
 * Thrown when a custom SQL review rule is malformed — a missing or undecodable condition, a tree
 * past the depth or leaf limits, an empty list operand, an over-long or uncompilable regex, or a
 * missing / over-long message (#1009). The message is resolved in the caller's locale at the throw
 * site.
 */
public final class IllegalSqlReviewCustomRuleException extends RuntimeException {

    public IllegalSqlReviewCustomRuleException(String message) {
        super(message);
    }

    public IllegalSqlReviewCustomRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
