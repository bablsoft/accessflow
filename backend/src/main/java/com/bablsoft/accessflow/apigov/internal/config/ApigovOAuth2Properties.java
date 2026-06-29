package com.bablsoft.accessflow.apigov.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Deployment-wide tunables for outbound OAuth2 token sourcing on API connectors (AF-500 / #506).
 * Per-connector token endpoint, credentials, scopes, grant type, and client-auth method live on the
 * {@code api_connectors} row; these only cover the cache safety-skew, the fallback TTL used when a
 * token response omits {@code expires_in}, the token-endpoint HTTP timeouts, and the
 * repeated-failure alert threshold.
 */
@ConfigurationProperties("accessflow.apigov")
public record ApigovOAuth2Properties(
        Duration oauth2TokenCacheSkew,
        Duration oauth2TokenRequestTimeout,
        Duration oauth2TokenFallbackTtl,
        int oauth2TokenFailureAlertThreshold) {

    public ApigovOAuth2Properties {
        if (oauth2TokenCacheSkew == null || oauth2TokenCacheSkew.isNegative()) {
            oauth2TokenCacheSkew = Duration.ofSeconds(30);
        }
        if (oauth2TokenRequestTimeout == null || oauth2TokenRequestTimeout.isZero()
                || oauth2TokenRequestTimeout.isNegative()) {
            oauth2TokenRequestTimeout = Duration.ofSeconds(10);
        }
        if (oauth2TokenFallbackTtl == null || oauth2TokenFallbackTtl.isZero()
                || oauth2TokenFallbackTtl.isNegative()) {
            oauth2TokenFallbackTtl = Duration.ofSeconds(60);
        }
        if (oauth2TokenFailureAlertThreshold <= 0) {
            oauth2TokenFailureAlertThreshold = 3;
        }
    }
}
