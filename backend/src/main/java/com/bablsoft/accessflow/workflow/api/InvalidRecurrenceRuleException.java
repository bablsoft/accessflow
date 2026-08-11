package com.bablsoft.accessflow.workflow.api;

/**
 * Thrown at submission time when a query's recurrence definition is invalid (#627): the rule is
 * neither a 6-field Spring cron expression nor an ISO-8601 duration, the mandatory expiry is
 * missing or not in the future, the rule collides with {@code scheduled_for}, or the cadence is
 * shorter than the configured minimum interval. The message is localized at the throw site and
 * mapped to HTTP 400 {@code RECURRENCE_INVALID}.
 */
public final class InvalidRecurrenceRuleException extends RuntimeException {

    public InvalidRecurrenceRuleException(String message) {
        super(message);
    }
}
