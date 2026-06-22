package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when an organization has consumed its monthly AI token budget
 * ({@code accessflow.ai.rate-limit.tokens-per-month}, AF-55), measured as the summed
 * {@code prompt_tokens + completion_tokens} of the org's {@code ai_analyses} rows in the current
 * calendar month. Extends {@link AiAnalysisException} so the async submitted-query path routes it to
 * a sentinel {@code CRITICAL} analysis row; the synchronous preview / text-to-SQL paths map it to
 * HTTP 429.
 */
public class AiBudgetExceededException extends AiAnalysisException {

    private final long budget;
    private final long used;

    public AiBudgetExceededException(long budget, long used) {
        super("AI monthly token budget exhausted: used " + used + " of " + budget + " tokens");
        this.budget = budget;
        this.used = used;
    }

    public long budget() {
        return budget;
    }

    public long used() {
        return used;
    }
}
