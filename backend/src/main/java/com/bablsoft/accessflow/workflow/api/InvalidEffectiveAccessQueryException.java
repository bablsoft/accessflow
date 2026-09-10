package com.bablsoft.accessflow.workflow.api;

/**
 * The reverse-index query cannot be answered as asked (issue AF-859) — currently only a {@code table}
 * that normalizes away to nothing, such as {@code "[]"} or a lone quote.
 *
 * <p>Checked in the service rather than declared as a Bean Validation constraint on the controller
 * because the rule <em>is</em> the enforcement gate's normalization: whether a spelling survives it
 * is not something a regex on the raw parameter can decide without becoming a second, drifting copy
 * of that rule. Maps to HTTP 400.
 */
public final class InvalidEffectiveAccessQueryException extends RuntimeException {

    public InvalidEffectiveAccessQueryException(String message) {
        super(message);
    }
}
