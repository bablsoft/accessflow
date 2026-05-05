package com.partqam.accessflow.ai.api;

import com.partqam.accessflow.core.api.DbType;

/**
 * Pluggable adapter that performs the actual AI inference call. One implementation per provider
 * (Anthropic, OpenAI, Ollama). Selection happens via {@code accessflow.ai.provider} config.
 *
 * <p>Implementations must be thread-safe and must not mutate caller-supplied arguments.
 */
public interface AiAnalyzerStrategy {

    /**
     * Analyze {@code sql} for risks, returning a structured result. The {@code schemaContext}
     * argument is an opaque, provider-renderable description of the target schema (or
     * {@code null}/empty if introspection is unavailable).
     *
     * @throws com.partqam.accessflow.ai.api.AiAnalysisException      provider call failed
     * @throws com.partqam.accessflow.ai.api.AiAnalysisParseException provider response did not
     *                                                                match the expected schema
     */
    AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext);
}
