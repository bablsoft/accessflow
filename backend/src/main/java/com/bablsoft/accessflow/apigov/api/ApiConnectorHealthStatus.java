package com.bablsoft.accessflow.apigov.api;

/** Outcome of the latest scheduled connector probe; {@code UNKNOWN} until one has run. */
public enum ApiConnectorHealthStatus {
    UNKNOWN,
    HEALTHY,
    UNHEALTHY
}
