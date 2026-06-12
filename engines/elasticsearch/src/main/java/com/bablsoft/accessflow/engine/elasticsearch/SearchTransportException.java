package com.bablsoft.accessflow.engine.elasticsearch;

/**
 * Transport-neutral failure raised by a {@link SearchTransport} so the executor and the admin probes
 * never see a driver-specific exception type (Elastic vs OpenSearch {@code ResponseException}).
 * {@code statusCode > 0} is an HTTP error carrying the cluster's response {@code body};
 * {@code statusCode == 0} is a connect/IO failure, with {@code timeout} distinguishing a
 * connect/socket timeout. Mapped to the host's engine-neutral execution exceptions by
 * {@link EsExceptionTranslator}.
 */
class SearchTransportException extends RuntimeException {

    private final int statusCode;
    private final transient String responseBody;
    private final boolean timeout;

    SearchTransportException(int statusCode, String responseBody, boolean timeout, Throwable cause) {
        super(message(statusCode, responseBody, timeout), cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.timeout = timeout;
    }

    int statusCode() {
        return statusCode;
    }

    String responseBody() {
        return responseBody;
    }

    boolean timeout() {
        return timeout;
    }

    private static String message(int statusCode, String responseBody, boolean timeout) {
        if (timeout) {
            return "Search request timed out";
        }
        if (statusCode > 0) {
            return "Search request failed with HTTP " + statusCode
                    + (responseBody == null ? "" : ": " + responseBody);
        }
        return "Search transport error";
    }
}
