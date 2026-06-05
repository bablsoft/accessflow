package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.UUID;

/**
 * Decorates a provider delegate to emit a Langfuse trace per {@code analyze} call. The
 * organization, provider and model are captured from the {@code ai_config} row at build time; the
 * trace is fired on both success and failure (the original exception is always rethrown). Tracing
 * is best-effort — {@link LangfuseTracer} dispatches asynchronously and this decorator additionally
 * guards against the resolve step throwing, so analysis is never affected.
 */
class TracingAiAnalyzerStrategy implements AiAnalyzerStrategy {

    private static final Logger log = LoggerFactory.getLogger(TracingAiAnalyzerStrategy.class);

    private final AiAnalyzerStrategy delegate;
    private final LangfuseTracer tracer;
    private final UUID organizationId;
    private final AiProviderType provider;
    private final String model;
    private final Clock clock;

    TracingAiAnalyzerStrategy(AiAnalyzerStrategy delegate, LangfuseTracer tracer, UUID organizationId,
                              AiProviderType provider, String model, Clock clock) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.organizationId = organizationId;
        this.provider = provider;
        this.model = model;
        this.clock = clock;
    }

    @Override
    public AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext, String language,
                                    UUID aiConfigId) {
        var start = clock.instant();
        try {
            var result = delegate.analyze(sql, dbType, schemaContext, language, aiConfigId);
            safeTrace(() -> tracer.trace(LangfuseTraceContext.success(
                    organizationId, aiConfigId, provider, model, sql, dbType, schemaContext, language,
                    result, start, clock.instant())));
            return result;
        } catch (RuntimeException e) {
            safeTrace(() -> tracer.trace(LangfuseTraceContext.failure(
                    organizationId, aiConfigId, provider, model, sql, dbType, schemaContext, language,
                    e.getMessage(), start, clock.instant())));
            throw e;
        }
    }

    @Override
    public GeneratedSqlResult generateSql(String prompt, DbType dbType, String schemaContext,
                                          String language, UUID aiConfigId) {
        // v1: SQL generation is not yet traced to Langfuse (the trace context is analysis-shaped).
        // Delegate straight through; tracing parity is a follow-up.
        return delegate.generateSql(prompt, dbType, schemaContext, language, aiConfigId);
    }

    private void safeTrace(Runnable trace) {
        try {
            trace.run();
        } catch (RuntimeException ex) {
            log.warn("Langfuse tracing dispatch failed for org={}: {}", organizationId, ex.getMessage());
        }
    }
}
