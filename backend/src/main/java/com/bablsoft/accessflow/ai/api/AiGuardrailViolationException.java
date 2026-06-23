package com.bablsoft.accessflow.ai.api;

/**
 * Thrown by the guardrail decorator (AF-450) when a submitted SQL / NL prompt matches a configured
 * block pattern, before any model call. Extends {@link AiAnalysisException} so the async analysis
 * path records a sentinel {@code CRITICAL} row; the editor-preview path maps it to HTTP 422.
 * The matched pattern is retained for logging — it is never echoed to the client.
 */
public class AiGuardrailViolationException extends AiAnalysisException {

    private final transient String pattern;

    public AiGuardrailViolationException(String message, String pattern) {
        super(message);
        this.pattern = pattern;
    }

    public String pattern() {
        return pattern;
    }
}
