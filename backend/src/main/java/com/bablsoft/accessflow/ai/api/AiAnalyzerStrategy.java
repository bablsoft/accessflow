package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.DbType;

import java.util.UUID;

/**
 * Pluggable adapter that performs the actual AI inference call. One implementation per provider
 * (Anthropic, OpenAI, Ollama). The autowired bean is the {@code AiAnalyzerStrategyHolder}, which
 * resolves a delegate per {@code ai_config} row and caches it.
 *
 * <p>Implementations must be thread-safe and must not mutate caller-supplied arguments.
 */
public interface AiAnalyzerStrategy {

    /**
     * Analyze {@code sql} for risks, returning a structured result. The {@code schemaContext}
     * argument is an opaque, provider-renderable description of the target schema (or
     * {@code null}/empty if introspection is unavailable).
     *
     * <p>{@code language} is a BCP-47 code (e.g. {@code "en"}, {@code "es"}, {@code "zh-CN"}) that
     * tells the AI which language to use for free-form fields. When {@code null} or unrecognised
     * the adapter falls back to English.
     *
     * <p>{@code aiConfigId} scopes the provider lookup: the holder resolves the corresponding
     * {@code ai_config} row and builds/caches a provider-specific delegate for that row.
     *
     * @throws com.bablsoft.accessflow.ai.api.AiAnalysisException      provider call failed or AI is
     *                                                                not configured
     * @throws com.bablsoft.accessflow.ai.api.AiAnalysisParseException provider response did not
     *                                                                match the expected schema
     */
    AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext, String language,
                             UUID aiConfigId);
}
