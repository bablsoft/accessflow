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

    /**
     * Translate the natural-language {@code prompt} into a single SQL statement for the target
     * dialect. The {@code schemaContext}, {@code language} and {@code aiConfigId} arguments carry the
     * same semantics as {@link #analyze}: schema is an opaque description (or {@code null}/empty),
     * language is a BCP-47 code, and {@code aiConfigId} scopes the provider lookup.
     *
     * <p>The returned SQL is a <em>draft</em> — it is not parsed, validated or executed here. The
     * caller submits it through the regular query pipeline, where JSqlParser validation, permission
     * checks, AI risk analysis and human review still apply.
     *
     * @throws com.bablsoft.accessflow.ai.api.AiAnalysisException      provider call failed or AI is
     *                                                                not configured
     * @throws com.bablsoft.accessflow.ai.api.AiAnalysisParseException provider response did not
     *                                                                contain a usable SQL statement
     */
    GeneratedSqlResult generateSql(String prompt, DbType dbType, String schemaContext, String language,
                                   UUID aiConfigId);
}
