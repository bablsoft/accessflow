package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLangfuseTracerTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID AI_CONFIG_ID = UUID.randomUUID();
    private static final Executor INLINE = Runnable::run;

    @Mock LangfuseConfigResolver configResolver;
    @Mock LangfuseClient client;

    private DefaultLangfuseTracer tracer() {
        return new DefaultLangfuseTracer(configResolver, client, INLINE);
    }

    private static ResolvedLangfuseConfig resolved(boolean tracing) {
        return new ResolvedLangfuseConfig("https://lf.example.com/", "pk", "sk", tracing, false);
    }

    private static AiAnalysisResult result() {
        return new AiAnalysisResult(42, RiskLevel.MEDIUM, "summary", List.of(), false, null,
                AiProviderType.OPENAI, "gpt-4o", 100, 20, List.of());
    }

    private static LangfuseTraceContext successCtx() {
        return LangfuseTraceContext.success(ORG_ID, AI_CONFIG_ID, AiProviderType.OPENAI, "gpt-4o",
                "SELECT 1", DbType.POSTGRESQL, "schema", "en", result(),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"));
    }

    @Test
    void skipsWhenResolverEmpty() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.empty());
        tracer().trace(successCtx());
        verify(client, never()).ingest(any(), any());
    }

    @Test
    void skipsWhenTracingDisabled() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(false)));
        tracer().trace(successCtx());
        verify(client, never()).ingest(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestsTraceAndGenerationOnSuccess() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(true)));

        tracer().trace(successCtx());

        var bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(client).ingest(any(), bodyCaptor.capture());
        var batch = (List<Map<String, Object>>) ((Map<String, Object>) bodyCaptor.getValue()).get("batch");
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).get("type")).isEqualTo("trace-create");
        assertThat(batch.get(1).get("type")).isEqualTo("generation-create");
        var generation = (Map<String, Object>) batch.get(1).get("body");
        assertThat(generation.get("model")).isEqualTo("gpt-4o");
        assertThat(generation.get("level")).isEqualTo("DEFAULT");
        assertThat(generation).containsKey("usageDetails");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestsErrorLevelOnFailureContext() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(true)));
        var ctx = LangfuseTraceContext.failure(ORG_ID, AI_CONFIG_ID, AiProviderType.OPENAI, "gpt-4o",
                "SELECT 1", DbType.POSTGRESQL, null, "en", "boom",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"));

        tracer().trace(ctx);

        var bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(client).ingest(any(), bodyCaptor.capture());
        var batch = (List<Map<String, Object>>) ((Map<String, Object>) bodyCaptor.getValue()).get("batch");
        var generation = (Map<String, Object>) batch.get(1).get("body");
        assertThat(generation.get("level")).isEqualTo("ERROR");
        assertThat(generation.get("statusMessage")).isEqualTo("boom");
        assertThat(generation.get("output")).isNull();
        assertThat(generation).doesNotContainKey("usageDetails");
    }

    @Test
    void swallowsClientFailure() {
        when(configResolver.resolve(ORG_ID)).thenReturn(Optional.of(resolved(true)));
        org.mockito.Mockito.doThrow(new RuntimeException("ingest failed"))
                .when(client).ingest(any(), any());

        assertThatCode(() -> tracer().trace(successCtx())).doesNotThrowAnyException();
    }
}
