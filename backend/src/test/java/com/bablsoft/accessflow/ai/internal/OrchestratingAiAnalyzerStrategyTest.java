package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.VotingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratingAiAnalyzerStrategyTest {

    private static final UUID CONFIG_ID = UUID.randomUUID();
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock AiAnalyzerStrategy primaryStrategy;
    @Mock AiAnalyzerStrategy memberStrategy;

    private static AiAnalysisResult result(AiProviderType provider, String model, int score,
                                           RiskLevel level, int promptTokens, int completionTokens) {
        return new AiAnalysisResult(score, level, "by " + model, List.of(), false, null, provider,
                model, promptTokens, completionTokens, List.of());
    }

    private OrchestratingAiAnalyzerStrategy orchestrator(VotingStrategy strategy,
                                                         OrchestratingAiAnalyzerStrategy.Member... members) {
        return new OrchestratingAiAnalyzerStrategy(List.of(members), strategy, fixedClock);
    }

    @Test
    void aggregatesAllSuccessfulMembersAndSumsTokens() {
        when(primaryStrategy.analyze(any(), any(), any(), any(), any()))
                .thenReturn(result(AiProviderType.ANTHROPIC, "claude", 40, RiskLevel.MEDIUM, 100, 20));
        when(memberStrategy.analyze(any(), any(), any(), any(), any()))
                .thenReturn(result(AiProviderType.OLLAMA, "llama", 80, RiskLevel.CRITICAL, 50, 10));

        var orchestrator = orchestrator(VotingStrategy.MAX_RISK,
                new OrchestratingAiAnalyzerStrategy.Member(primaryStrategy, AiProviderType.ANTHROPIC, "claude", 1.0),
                new OrchestratingAiAnalyzerStrategy.Member(memberStrategy, AiProviderType.OLLAMA, "llama", 1.0));

        var result = orchestrator.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", CONFIG_ID);

        // MAX_RISK → the llama member wins the score/level.
        assertThat(result.riskScore()).isEqualTo(80);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        // aggregate provider/model = primary
        assertThat(result.aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(result.aiModel()).isEqualTo("claude");
        // tokens summed across successes
        assertThat(result.promptTokens()).isEqualTo(150);
        assertThat(result.completionTokens()).isEqualTo(30);
        // per-model breakdown for both members
        assertThat(result.modelResults()).hasSize(2);
        assertThat(result.modelResults()).allMatch(m -> !m.failed());
    }

    @Test
    void partialFailureAggregatesSurvivorsAndRecordsFailedMember() {
        when(primaryStrategy.analyze(any(), any(), any(), any(), any()))
                .thenReturn(result(AiProviderType.ANTHROPIC, "claude", 55, RiskLevel.HIGH, 100, 20));
        when(memberStrategy.analyze(any(), any(), any(), any(), any()))
                .thenThrow(new AiAnalysisException("provider down"));

        var orchestrator = orchestrator(VotingStrategy.WEIGHTED_AVERAGE,
                new OrchestratingAiAnalyzerStrategy.Member(primaryStrategy, AiProviderType.ANTHROPIC, "claude", 1.0),
                new OrchestratingAiAnalyzerStrategy.Member(memberStrategy, AiProviderType.OLLAMA, "llama", 1.0));

        var result = orchestrator.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", CONFIG_ID);

        assertThat(result.riskScore()).isEqualTo(55); // only the survivor counts
        assertThat(result.modelResults()).hasSize(2);
        var failed = result.modelResults().stream().filter(m -> m.failed()).toList();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).model()).isEqualTo("llama");
        assertThat(failed.get(0).errorMessage()).isEqualTo("provider down");
        assertThat(failed.get(0).riskScore()).isNull();
    }

    @Test
    void rethrowsWhenEveryMemberFails() {
        when(primaryStrategy.analyze(any(), any(), any(), any(), any()))
                .thenThrow(new AiAnalysisException("p1 down"));
        when(memberStrategy.analyze(any(), any(), any(), any(), any()))
                .thenThrow(new AiAnalysisException("p2 down"));

        var orchestrator = orchestrator(VotingStrategy.WEIGHTED_AVERAGE,
                new OrchestratingAiAnalyzerStrategy.Member(primaryStrategy, AiProviderType.ANTHROPIC, "claude", 1.0),
                new OrchestratingAiAnalyzerStrategy.Member(memberStrategy, AiProviderType.OLLAMA, "llama", 1.0));

        assertThatThrownBy(() -> orchestrator.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", CONFIG_ID))
                .isInstanceOf(AiAnalysisException.class);
    }

    @Test
    void singleMemberDegeneratesToThatMemberWithOneBreakdownRow() {
        when(primaryStrategy.analyze(any(), any(), any(), any(), any()))
                .thenReturn(result(AiProviderType.OPENAI, "gpt", 30, RiskLevel.MEDIUM, 10, 5));

        var orchestrator = orchestrator(VotingStrategy.WEIGHTED_AVERAGE,
                new OrchestratingAiAnalyzerStrategy.Member(primaryStrategy, AiProviderType.OPENAI, "gpt", 1.0));

        var result = orchestrator.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", CONFIG_ID);

        assertThat(result.riskScore()).isEqualTo(30);
        assertThat(result.modelResults()).hasSize(1);
        assertThat(result.modelResults().get(0).model()).isEqualTo("gpt");
        assertThat(result.modelResults().get(0).promptTokens()).isEqualTo(10);
    }

    @Test
    void generateSqlDelegatesToPrimaryMemberOnly() {
        var generated = new GeneratedSqlResult("SELECT 1", AiProviderType.OPENAI, "gpt", 1, 1);
        when(primaryStrategy.generateSql(any(), any(), any(), any(), any())).thenReturn(generated);

        var orchestrator = orchestrator(VotingStrategy.WEIGHTED_AVERAGE,
                new OrchestratingAiAnalyzerStrategy.Member(primaryStrategy, AiProviderType.OPENAI, "gpt", 1.0),
                new OrchestratingAiAnalyzerStrategy.Member(memberStrategy, AiProviderType.OLLAMA, "llama", 1.0));

        var returned = orchestrator.generateSql("orders", DbType.POSTGRESQL, null, "en", CONFIG_ID);

        assertThat(returned).isSameAs(generated);
        verify(primaryStrategy).generateSql(any(), any(), any(), any(), any());
        verify(memberStrategy, never()).generateSql(any(), any(), any(), any(), any());
    }

    @Test
    void capturesPerMemberLatencyFromClock() {
        // Single member runs synchronously: clock is read at start then end → deterministic latency.
        var steppingClock = org.mockito.Mockito.mock(Clock.class);
        when(steppingClock.instant()).thenReturn(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00.250Z"));
        when(primaryStrategy.analyze(any(), any(), any(), any(), any()))
                .thenReturn(result(AiProviderType.OPENAI, "gpt", 30, RiskLevel.MEDIUM, 10, 5));

        var orchestrator = new OrchestratingAiAnalyzerStrategy(
                List.of(new OrchestratingAiAnalyzerStrategy.Member(primaryStrategy, AiProviderType.OPENAI, "gpt", 1.0)),
                VotingStrategy.WEIGHTED_AVERAGE, steppingClock);

        var result = orchestrator.analyze("SELECT 1", DbType.POSTGRESQL, null, "en", CONFIG_ID);

        assertThat(result.modelResults().get(0).latencyMs()).isEqualTo(250L);
    }

    @Test
    void rejectsEmptyMemberList() {
        assertThatThrownBy(() -> new OrchestratingAiAnalyzerStrategy(List.of(),
                VotingStrategy.MAX_RISK, fixedClock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
