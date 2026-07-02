package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestGroupItemResponseTest {

    private RequestGroupItemView view(QueryDetailView.AiAnalysisDetail analysis) {
        return new RequestGroupItemView(UUID.randomUUID(), 0, RequestGroupTargetKind.QUERY,
                UUID.randomUUID(), "prod-db", "SELECT 1", QueryType.SELECT, false, null, null, null,
                null, null, analysis == null ? null : analysis.id(), RiskLevel.MEDIUM, 40, analysis,
                RequestGroupItemStatus.PENDING, null, null, null, null, null);
    }

    @Test
    void mapsEmbeddedAnalysisWithRawJsonAndFallbacks() {
        var analysisId = UUID.randomUUID();
        var detail = new QueryDetailView.AiAnalysisDetail(analysisId, RiskLevel.MEDIUM, 40,
                "Reads one row", "[{\"severity\":\"LOW\"}]", null, true, 5L, AiProviderType.OPENAI,
                "gpt-4o", 12, 7, false, null);

        var response = RequestGroupItemResponse.from(view(detail));

        var analysis = response.aiAnalysis();
        assertThat(analysis).isNotNull();
        assertThat(analysis.id()).isEqualTo(analysisId);
        assertThat(analysis.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(analysis.riskScore()).isEqualTo(40);
        assertThat(analysis.summary()).isEqualTo("Reads one row");
        assertThat(analysis.issues()).isEqualTo("[{\"severity\":\"LOW\"}]");
        assertThat(analysis.optimizations()).isEqualTo("[]");
        assertThat(analysis.missingIndexesDetected()).isTrue();
        assertThat(analysis.affectsRowEstimate()).isEqualTo(5L);
        assertThat(analysis.aiProvider()).isEqualTo(AiProviderType.OPENAI);
        assertThat(analysis.aiModel()).isEqualTo("gpt-4o");
        assertThat(analysis.promptTokens()).isEqualTo(12);
        assertThat(analysis.completionTokens()).isEqualTo(7);
        assertThat(analysis.failed()).isFalse();
    }

    @Test
    void mapsNullAnalysisToNull() {
        var response = RequestGroupItemResponse.from(view(null));

        assertThat(response.aiAnalysis()).isNull();
        assertThat(response.sqlText()).isEqualTo("SELECT 1");
        assertThat(response.aiRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void carriesFailureStateOfAnalysis() {
        var detail = new QueryDetailView.AiAnalysisDetail(UUID.randomUUID(), RiskLevel.CRITICAL, 100,
                "AI analysis failed", null, null, false, null, AiProviderType.ANTHROPIC, "claude",
                0, 0, true, "provider unavailable");

        var analysis = RequestGroupItemResponse.from(view(detail)).aiAnalysis();

        assertThat(analysis.failed()).isTrue();
        assertThat(analysis.errorMessage()).isEqualTo("provider unavailable");
        assertThat(analysis.issues()).isEqualTo("[]");
        assertThat(analysis.optimizations()).isEqualTo("[]");
    }
}
