package com.bablsoft.accessflow.ai.internal;

import java.util.UUID;

/**
 * Enforces the per-organization AI guardrails (AF-55) before any {@code AiAnalyzerStrategy} call:
 * a per-minute request rate limit and a monthly token budget. Throws
 * {@code AiRateLimitExceededException} / {@code AiBudgetExceededException} (both subtypes of
 * {@code AiAnalysisException}) when a limit is exceeded; a {@code null} organization is a no-op.
 *
 * <p>The in-app help chat runtime enforces this org-wide limit too, then its own per-user limit on
 * top (AF-903): without the second one, a single user holding Enter would drain the organization's
 * whole per-minute budget — including the share the SQL analyzer needs.
 *
 * <p>{@code public} rather than package-private because {@code ai.internal.help} is a sub-package
 * and gets no package-private access (epic AF-899 decision 5). It stays inside {@code ai.internal},
 * so it remains module-private to the rest of the application.
 */
public interface AiRateLimiter {

    void enforce(UUID organizationId);
}
