package com.bablsoft.accessflow.sqlreview.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for deterministic SQL review, bound from {@code accessflow.sqlreview.*}.
 *
 * <p>One constructor only. A second convenience constructor silently unbinds every property.
 *
 * @param customRuleCacheTtl how long an organization's custom rules (#1009) are cached before they
 *                           are re-read. An edit evicts the cache at once on the replica that made
 *                           it; this bounds how long another replica keeps evaluating the old rules.
 *                           {@code null}, zero or negative falls back to one minute.
 */
@ConfigurationProperties("accessflow.sqlreview")
public record SqlReviewProperties(Duration customRuleCacheTtl) {

    public static final Duration DEFAULT_CUSTOM_RULE_CACHE_TTL = Duration.ofMinutes(1);

    public SqlReviewProperties {
        if (customRuleCacheTtl == null || customRuleCacheTtl.isNegative() || customRuleCacheTtl.isZero()) {
            customRuleCacheTtl = DEFAULT_CUSTOM_RULE_CACHE_TTL;
        }
    }
}
