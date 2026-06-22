package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-organization AI guardrails (AF-55), bound from {@code accessflow.ai.rate-limit.*}. Both limits
 * are enforced before any {@code AiAnalyzerStrategy} call so a runaway editor or compromised account
 * cannot drain the provider API key / monthly budget.
 *
 * <ul>
 *   <li>{@code requestsPerMinute} — fixed-window per-org request cap (Redis counter); default 30.
 *       A value {@code <= 0} disables the per-minute limit.</li>
 *   <li>{@code tokensPerMonth} — monthly token budget enforced against the summed
 *       {@code prompt_tokens + completion_tokens} of the org's {@code ai_analyses} rows in the
 *       current calendar month; default 0 (unlimited / opt-in). A value {@code <= 0} disables the
 *       budget.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.ai.rate-limit")
public record AiRateLimitProperties(Integer requestsPerMinute, Long tokensPerMonth) {

    public AiRateLimitProperties {
        if (requestsPerMinute == null) {
            requestsPerMinute = 30;
        }
        if (tokensPerMonth == null) {
            tokensPerMonth = 0L;
        }
    }
}
