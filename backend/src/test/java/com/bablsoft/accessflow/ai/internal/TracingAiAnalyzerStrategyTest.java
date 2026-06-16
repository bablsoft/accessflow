package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TracingAiAnalyzerStrategyTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID AI_CONFIG_ID = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock AiAnalyzerStrategy delegate;
    @Mock LangfuseTracer tracer;

    private TracingAiAnalyzerStrategy strategy() {
        return new TracingAiAnalyzerStrategy(delegate, tracer, ORG_ID, AiProviderType.OPENAI, "gpt-4o", clock);
    }

    private static AiAnalysisResult result() {
        return new AiAnalysisResult(10, RiskLevel.LOW, "ok", List.of(), false, null,
                AiProviderType.OPENAI, "gpt-4o", 1, 1, List.of());
    }

    @Test
    void returnsResultAndFiresSuccessTrace() {
        var result = result();
        when(delegate.analyze("SELECT 1", DbType.POSTGRESQL, "schema", "en", AI_CONFIG_ID)).thenReturn(result);

        var returned = strategy().analyze("SELECT 1", DbType.POSTGRESQL, "schema", "en", AI_CONFIG_ID);

        assertThat(returned).isSameAs(result);
        var captor = ArgumentCaptor.forClass(LangfuseTraceContext.class);
        verify(tracer).trace(captor.capture());
        assertThat(captor.getValue().result()).isSameAs(result);
        assertThat(captor.getValue().errorMessage()).isNull();
        assertThat(captor.getValue().organizationId()).isEqualTo(ORG_ID);
    }

    @Test
    void firesErrorTraceAndRethrowsOnFailure() {
        when(delegate.analyze(any(), any(), any(), any(), any()))
                .thenThrow(new AiAnalysisException("boom"));

        assertThatThrownBy(() -> strategy().analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID))
                .isInstanceOf(AiAnalysisException.class)
                .hasMessageContaining("boom");

        var captor = ArgumentCaptor.forClass(LangfuseTraceContext.class);
        verify(tracer).trace(captor.capture());
        assertThat(captor.getValue().result()).isNull();
        assertThat(captor.getValue().errorMessage()).isEqualTo("boom");
    }

    @Test
    void tracerFailureDoesNotBreakAnalysis() {
        var result = result();
        when(delegate.analyze(any(), any(), any(), any(), any())).thenReturn(result);
        org.mockito.Mockito.doThrow(new RuntimeException("tracer down")).when(tracer).trace(any());

        var returned = strategy().analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);

        assertThat(returned).isSameAs(result);
    }

    @Test
    void delegateInvokedExactlyOnce() {
        when(delegate.analyze(any(), any(), any(), any(), any())).thenReturn(result());
        strategy().analyze("SELECT 1", DbType.POSTGRESQL, null, "en", AI_CONFIG_ID);
        verify(delegate).analyze(any(), any(), any(), any(), any());
    }

    @Test
    void generateSqlDelegatesWithoutTracing() {
        var generated = new com.bablsoft.accessflow.ai.api.GeneratedSqlResult(
                "SELECT 1", AiProviderType.OPENAI, "gpt-4o", 1, 1);
        when(delegate.generateSql("orders", DbType.POSTGRESQL, "schema", "en", AI_CONFIG_ID))
                .thenReturn(generated);

        var returned = strategy().generateSql("orders", DbType.POSTGRESQL, "schema", "en", AI_CONFIG_ID);

        assertThat(returned).isSameAs(generated);
        verify(delegate).generateSql("orders", DbType.POSTGRESQL, "schema", "en", AI_CONFIG_ID);
        org.mockito.Mockito.verifyNoInteractions(tracer);
    }
}
