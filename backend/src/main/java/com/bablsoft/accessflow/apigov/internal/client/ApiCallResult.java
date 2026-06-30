package com.bablsoft.accessflow.apigov.internal.client;

/**
 * Raw outcome of an executed outbound API call (before response-field masking). {@code contentType}
 * is the upstream {@code Content-Type} header (null when absent) — kept so the stored snapshot can be
 * downloaded in its correct format.
 */
public record ApiCallResult(int statusCode, int durationMs, long bytes, boolean truncated, String body,
                            String contentType) {
}
