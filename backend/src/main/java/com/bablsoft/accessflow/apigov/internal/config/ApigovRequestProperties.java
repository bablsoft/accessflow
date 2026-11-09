package com.bablsoft.accessflow.apigov.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for governed API request composition (AF-517) and response storage (AF-521).
 * {@code maxRequestBodyBytes} caps the total encoded size of a submitted request body (raw text,
 * x-www-form-urlencoded, or the base64-decoded size of form-data / binary file parts) — files are
 * carried inline as bounded base64 since AccessFlow has no object storage. Exceeding it rejects the
 * submission with HTTP 422. {@code maxResponseBytes} is the system-wide hard ceiling on a stored
 * (and therefore downloadable) API response body — the absolute backstop above any per-connector
 * {@code max_response_bytes}. {@code responsePreviewBytes} bounds how much of the stored snapshot is
 * embedded inline in the detail view; the full body is served by the download endpoint.
 * {@code maxVariableValueBytes} caps a single resolved dynamic-variable value (AF-613) — a digest or
 * nonce is tens of bytes, so a large value means a runaway expression or an abusive override.
 * {@code maxDiffEntries} caps the entries returned by the response diff (#887).
 * {@code healthFailureThreshold} is the consecutive scheduled-probe failure count that flips a
 * connector to {@code UNHEALTHY}, and {@code healthHistoryRetention} bounds the probe history (#894).
 */
@ConfigurationProperties("accessflow.apigov")
public record ApigovRequestProperties(long maxRequestBodyBytes, long maxResponseBytes,
                                      long responsePreviewBytes, int maxVariableValueBytes,
                                      int maxDiffEntries, int healthFailureThreshold,
                                      Duration healthHistoryRetention) {

    public ApigovRequestProperties {
        if (maxRequestBodyBytes <= 0) {
            maxRequestBodyBytes = 5L * 1024 * 1024;
        }
        if (maxResponseBytes <= 0) {
            maxResponseBytes = 10L * 1024 * 1024;
        }
        if (responsePreviewBytes <= 0) {
            responsePreviewBytes = 65_536L;
        }
        if (maxVariableValueBytes <= 0) {
            maxVariableValueBytes = 8192;
        }
        if (maxDiffEntries <= 0) {
            maxDiffEntries = 500;
        }
        if (healthFailureThreshold <= 0) {
            healthFailureThreshold = 3;
        }
        if (healthHistoryRetention == null || healthHistoryRetention.isNegative()
                || healthHistoryRetention.isZero()) {
            healthHistoryRetention = Duration.ofDays(7);
        }
    }
}
