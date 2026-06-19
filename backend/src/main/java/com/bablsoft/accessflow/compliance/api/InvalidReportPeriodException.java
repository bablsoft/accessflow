package com.bablsoft.accessflow.compliance.api;

/**
 * Thrown when a compliance-report request has a missing, inverted, or too-large reporting period
 * (#459). The web layer maps this to HTTP 400 {@code INVALID_REPORT_PERIOD}; the {@code messageKey}
 * is resolved against the i18n bundle at the throw site's handler.
 */
public class InvalidReportPeriodException extends RuntimeException {

    private final String messageKey;

    public InvalidReportPeriodException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
