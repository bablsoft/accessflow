package com.bablsoft.accessflow.ai.internal;

import java.util.UUID;

/**
 * Enforces the per-organization AI guardrails (AF-55) before any {@code AiAnalyzerStrategy} call:
 * a per-minute request rate limit and a monthly token budget. Throws
 * {@code AiRateLimitExceededException} / {@code AiBudgetExceededException} (both subtypes of
 * {@code AiAnalysisException}) when a limit is exceeded; a {@code null} organization is a no-op.
 */
interface AiRateLimiter {

    void enforce(UUID organizationId);
}
