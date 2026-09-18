package com.bablsoft.accessflow.serviceaccounts.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-wide defaults for the per-identity API-key rate limiter (#873), bound from
 * {@code accessflow.serviceaccounts.rate-limit.*}. A {@code service_accounts} row's own
 * {@code rate_limit_per_minute} / {@code rate_limit_per_day} override these when set; a human's
 * personal API key (no detail row) always gets the defaults.
 *
 * <ul>
 *   <li>{@code requestsPerMinute} — fixed-window per-identity request cap; default 120. A value
 *       {@code <= 0} disables the per-minute limit.</li>
 *   <li>{@code requestsPerDay} — fixed-window per-identity daily cap; default 0 (unlimited). A value
 *       {@code <= 0} disables the daily limit.</li>
 * </ul>
 *
 * <p>Exactly one constructor: a second convenience constructor would silently unbind every property.
 */
@ConfigurationProperties("accessflow.serviceaccounts.rate-limit")
public record ServiceAccountRateLimitProperties(Integer requestsPerMinute, Integer requestsPerDay) {

    public static final int DEFAULT_REQUESTS_PER_MINUTE = 120;
    public static final int DEFAULT_REQUESTS_PER_DAY = 0;

    public ServiceAccountRateLimitProperties {
        if (requestsPerMinute == null) {
            requestsPerMinute = DEFAULT_REQUESTS_PER_MINUTE;
        }
        if (requestsPerDay == null) {
            requestsPerDay = DEFAULT_REQUESTS_PER_DAY;
        }
    }
}
