package com.bablsoft.accessflow.engine.databricks;

/**
 * Internal signal that a Statement Execution API call failed: an HTTP-level error (401/403,
 * 429/5xx, transport failure), a terminal {@code FAILED}/{@code CANCELED}/{@code CLOSED} statement
 * state (carrying the verbatim {@code status.error.message} + {@code error_code}), or the
 * host-computed execution deadline expiring while the statement was still running
 * ({@link #timedOut()} — the statement is cancelled best-effort first).
 * {@link DatabricksExceptionTranslator} maps it onto the engine-neutral {@code core.api}
 * execution exceptions.
 */
final class DatabricksApiException extends RuntimeException {

    private final String errorCode;
    private final int statusCode;
    private final boolean timedOut;

    DatabricksApiException(String message, String errorCode, int statusCode, boolean timedOut) {
        super(message);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
        this.timedOut = timedOut;
    }

    DatabricksApiException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
        this.statusCode = 0;
        this.timedOut = false;
    }

    String errorCode() {
        return errorCode;
    }

    int statusCode() {
        return statusCode;
    }

    boolean timedOut() {
        return timedOut;
    }
}
