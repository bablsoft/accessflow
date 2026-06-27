package com.bablsoft.accessflow.apigov.internal.client;

/** Raw outcome of an executed outbound API call (before response-field masking). */
public record ApiCallResult(int statusCode, int durationMs, long bytes, boolean truncated, String body) {
}
