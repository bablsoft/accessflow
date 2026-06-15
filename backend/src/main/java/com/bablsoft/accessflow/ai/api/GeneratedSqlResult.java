package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.AiProviderType;

/**
 * Result of a natural-language → query generation. {@code sql} is a draft statement (in the target
 * engine's query language — SQL, Mongo shell/JSON, Cypher, CQL, Elasticsearch Query DSL, redis-cli)
 * the caller pastes into the editor and submits through the normal query pipeline; it is never
 * executed here. {@code syntax} is the editor syntax id (matching the frontend {@code engineModes}
 * ids) so the editor mounts the correct mode for the draft. The provider / model / token fields
 * mirror {@link AiAnalysisResult} for observability.
 */
public record GeneratedSqlResult(
        String sql,
        AiProviderType aiProvider,
        String aiModel,
        int promptTokens,
        int completionTokens,
        String syntax) {

    /** Builds a result with no syntax hint yet — the service attaches it via {@link #withSyntax}. */
    public GeneratedSqlResult(String sql, AiProviderType aiProvider, String aiModel, int promptTokens,
                              int completionTokens) {
        this(sql, aiProvider, aiModel, promptTokens, completionTokens, null);
    }

    /** Returns a copy carrying the editor {@code syntax} id for the generated draft. */
    public GeneratedSqlResult withSyntax(String syntax) {
        return new GeneratedSqlResult(sql, aiProvider, aiModel, promptTokens, completionTokens, syntax);
    }
}
