package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.DeploymentAnalyzer;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDeploymentAnalyzerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID aiConfigId = UUID.randomUUID();

    private AiAnalyzerStrategy strategy;
    private AiRateLimiter rateLimiter;
    private DefaultDeploymentAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        strategy = mock(AiAnalyzerStrategy.class);
        rateLimiter = mock(AiRateLimiter.class);
        analyzer = new DefaultDeploymentAnalyzer(strategy, rateLimiter);
    }

    @Test
    void enforcesTheRateLimitBeforeCallingTheProvider() {
        when(strategy.analyze(any(), any(), any(), any(), any())).thenReturn(result());

        analyzer.analyzeDeployment(input("changelog: fix things"));

        var order = inOrder(rateLimiter, strategy);
        order.verify(rateLimiter).enforce(orgId);
        order.verify(strategy).analyze(any(), any(), any(), any(), any());
    }

    @Test
    void framesTheDeploymentAndDelegatesAsCustomDbType() {
        when(strategy.analyze(any(), any(), any(), any(), any())).thenReturn(result());

        var analysis = analyzer.analyzeDeployment(input("changelog: fix things"));

        assertThat(analysis.riskLevel()).isEqualTo(RiskLevel.HIGH);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(strategy).analyze(prompt.capture(), eq(DbType.CUSTOM), eq("changelog: fix things"),
                eq("en"), eq(aiConfigId));
        assertThat(prompt.getValue())
                .contains("GITHUB_ACTIONS")
                .contains("production")
                .contains("2.4.1")
                .contains("abc123")
                .contains("ghcr.io/app:2.4.1")
                .contains("changelog: fix things");
    }

    @Test
    void absentOptionalFieldsRenderAsNone() {
        when(strategy.analyze(any(), any(), any(), any(), any())).thenReturn(result());

        analyzer.analyzeDeployment(new DeploymentAnalyzer.DeploymentAnalysisInput(orgId, aiConfigId,
                "GENERIC", "staging", "1.0.0", null, "  ", null, null));

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(strategy).analyze(prompt.capture(), eq(DbType.CUSTOM), eq(null), eq(null),
                eq(aiConfigId));
        assertThat(prompt.getValue()).contains("(none)");
    }

    @Test
    void nullProviderAndEnvironmentDoNotBreakFraming() {
        when(strategy.analyze(any(), any(), any(), any(), any())).thenReturn(result());

        analyzer.analyzeDeployment(new DeploymentAnalyzer.DeploymentAnalysisInput(orgId, aiConfigId,
                null, null, null, null, null, null, null));

        verify(strategy).analyze(any(), eq(DbType.CUSTOM), eq(null), eq(null), eq(aiConfigId));
    }

    @Test
    void aStrictJsonParseFailurePropagatesToTheCaller() {
        when(strategy.analyze(any(), any(), any(), any(), any()))
                .thenThrow(new AiAnalysisParseException("risk_score must be in [0, 100]"));

        assertThatThrownBy(() -> analyzer.analyzeDeployment(input(null)))
                .isInstanceOf(AiAnalysisParseException.class);
    }

    private DeploymentAnalyzer.DeploymentAnalysisInput input(String metadataContext) {
        return new DeploymentAnalyzer.DeploymentAnalysisInput(orgId, aiConfigId, "GITHUB_ACTIONS",
                "production", "2.4.1", "abc123", "ghcr.io/app:2.4.1", metadataContext, "en");
    }

    private static AiAnalysisResult result() {
        return new AiAnalysisResult(80, RiskLevel.HIGH, "schema migration", List.of(), false, null,
                AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", 100, 50, List.of());
    }
}
