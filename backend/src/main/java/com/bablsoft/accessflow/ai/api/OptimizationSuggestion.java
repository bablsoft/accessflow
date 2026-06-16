package com.bablsoft.accessflow.ai.api;

/**
 * A concrete, actionable query-optimization suggestion produced by the AI analyzer. {@code sql} is a
 * ready-to-run statement in the datasource's dialect — for {@link OptimizationType#INDEX} an index
 * definition (e.g. {@code CREATE INDEX …}), for {@link OptimizationType#REWRITE} a rewritten version of
 * the submitted query. The caller may "apply" it as a draft: the statement is loaded into the editor
 * and submitted through the normal governance pipeline; it is never executed here.
 */
public record OptimizationSuggestion(
        OptimizationType type,
        String title,
        String rationale,
        String sql) {
}
