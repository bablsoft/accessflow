package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.core.api.AiAnalysisIssueCategoryView;
import com.bablsoft.accessflow.core.api.AiAnalysisModelStatView;
import com.bablsoft.accessflow.core.api.AiAnalysisRiskScoreBucketView;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsRaw;
import com.bablsoft.accessflow.core.api.AiAnalysisSubmitterView;
import com.bablsoft.accessflow.core.api.AiProviderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping test for the AF-450-extended AI analysis stats response (incl. per-model stats). */
class AiAnalysisStatsResponseMappingTest {

    @Test
    void mapsAllFourSeriesIncludingPerModelStats() {
        var userId = UUID.randomUUID();
        var raw = new AiAnalysisStatsRaw(
                List.of(new AiAnalysisRiskScoreBucketView(LocalDate.of(2026, 5, 10),
                        new BigDecimal("47.5"), 4L, 3L)),
                List.of(new AiAnalysisIssueCategoryView("performance", 12L)),
                List.of(new AiAnalysisSubmitterView(userId, "a@x.test", "Alice", 9L)),
                List.of(new AiAnalysisModelStatView(AiProviderType.ANTHROPIC, "claude", 6L, 1200L,
                        400L, new BigDecimal("850.0"), new BigDecimal("62.5"))));

        var response = AiAnalysisStatsResponse.from(raw);

        assertThat(response.riskScoreOverTime()).singleElement()
                .satisfies(p -> assertThat(p.totalCount()).isEqualTo(4L));
        assertThat(response.topIssueCategories()).singleElement()
                .satisfies(c -> assertThat(c.category()).isEqualTo("performance"));
        assertThat(response.topSubmitters()).singleElement()
                .satisfies(s -> assertThat(s.userId()).isEqualTo(userId));
        assertThat(response.perModelStats()).singleElement().satisfies(m -> {
            assertThat(m.provider()).isEqualTo(AiProviderType.ANTHROPIC);
            assertThat(m.model()).isEqualTo("claude");
            assertThat(m.analysisCount()).isEqualTo(6L);
            assertThat(m.totalPromptTokens()).isEqualTo(1200L);
            assertThat(m.totalCompletionTokens()).isEqualTo(400L);
            assertThat(m.avgLatencyMs()).isEqualByComparingTo(new BigDecimal("850.0"));
            assertThat(m.avgRiskScore()).isEqualByComparingTo(new BigDecimal("62.5"));
        });
    }

    @Test
    void emptyRawMapsToEmptySeries() {
        var response = AiAnalysisStatsResponse.from(
                new AiAnalysisStatsRaw(List.of(), List.of(), List.of(), List.of()));

        assertThat(response.perModelStats()).isEmpty();
        assertThat(response.riskScoreOverTime()).isEmpty();
    }
}
