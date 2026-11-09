package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;

/** One scheduled probe of a connector (#894). */
public record ApiConnectorHealthCheckView(Instant checkedAt, boolean success, String message, int durationMs) {
}
