package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.ApiCallAnalyzer;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiCallAnalyzerTest {

    @Mock private AiAnalyzerStrategy strategy;
    @Mock private AiRateLimiter rateLimiter;
    private DefaultApiCallAnalyzer analyzer;

    private final UUID orgId = UUID.randomUUID();
    private final UUID aiConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        analyzer = new DefaultApiCallAnalyzer(strategy, rateLimiter);
    }

    @Test
    void analyzeEnforcesRateLimitAndDelegatesAsCustomDbType() {
        var result = new AiAnalysisResult(40, RiskLevel.MEDIUM, "ok", List.of(), false, null,
                AiProviderType.ANTHROPIC, "claude", 1, 2, List.of());
        when(strategy.analyze(any(), eq(DbType.CUSTOM), any(), any(), eq(aiConfigId))).thenReturn(result);

        var out = analyzer.analyzeApiCall(new ApiCallAnalyzer.ApiCallAnalysisInput(orgId, aiConfigId,
                "REST", "POST", "/charges", "{}", "schema", "en"));

        assertThat(out.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        verify(rateLimiter).enforce(orgId);
        verify(strategy).analyze(any(), eq(DbType.CUSTOM), eq("schema"), eq("en"), eq(aiConfigId));
    }

    @Test
    void generateEnforcesRateLimitAndWrapsDraft() {
        when(strategy.generateSql(any(), eq(DbType.CUSTOM), any(), any(), eq(aiConfigId)))
                .thenReturn(new GeneratedSqlResult("POST /charges {}", AiProviderType.OPENAI, "gpt", 3, 4));

        var out = analyzer.generateApiCall(new ApiCallAnalyzer.ApiCallGenerationInput(orgId, aiConfigId,
                "REST", "charge a customer", "schema", "en"));

        assertThat(out.draft()).isEqualTo("POST /charges {}");
        assertThat(out.aiProvider()).isEqualTo(AiProviderType.OPENAI);
        verify(rateLimiter).enforce(orgId);
    }
}
