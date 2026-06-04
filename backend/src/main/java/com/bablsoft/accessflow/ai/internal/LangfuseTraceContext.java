package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything {@link LangfuseTracer} needs to emit one analysis trace. {@code result} is non-null on
 * success and {@code errorMessage} non-null on failure (never both). The configured provider/model
 * come from the {@code ai_config} row; the result carries the actual model/usage reported back.
 */
record LangfuseTraceContext(
        UUID organizationId,
        UUID aiConfigId,
        AiProviderType configuredProvider,
        String configuredModel,
        String sql,
        DbType dbType,
        String schemaContext,
        String language,
        AiAnalysisResult result,
        String errorMessage,
        Instant startTime,
        Instant endTime) {

    static LangfuseTraceContext success(UUID organizationId, UUID aiConfigId, AiProviderType provider,
                                        String model, String sql, DbType dbType, String schemaContext,
                                        String language, AiAnalysisResult result,
                                        Instant startTime, Instant endTime) {
        return new LangfuseTraceContext(organizationId, aiConfigId, provider, model, sql, dbType,
                schemaContext, language, result, null, startTime, endTime);
    }

    static LangfuseTraceContext failure(UUID organizationId, UUID aiConfigId, AiProviderType provider,
                                        String model, String sql, DbType dbType, String schemaContext,
                                        String language, String errorMessage,
                                        Instant startTime, Instant endTime) {
        return new LangfuseTraceContext(organizationId, aiConfigId, provider, model, sql, dbType,
                schemaContext, language, null, errorMessage, startTime, endTime);
    }
}
