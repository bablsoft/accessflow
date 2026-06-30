package com.bablsoft.accessflow.apigov.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for governed API request composition (AF-517). {@code maxRequestBodyBytes} caps the total
 * encoded size of a submitted request body (raw text, x-www-form-urlencoded, or the base64-decoded
 * size of form-data / binary file parts) — files are carried inline as bounded base64 since
 * AccessFlow has no object storage. Exceeding it rejects the submission with HTTP 422.
 */
@ConfigurationProperties("accessflow.apigov")
public record ApigovRequestProperties(long maxRequestBodyBytes) {

    public ApigovRequestProperties {
        if (maxRequestBodyBytes <= 0) {
            maxRequestBodyBytes = 5L * 1024 * 1024;
        }
    }
}
