package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.internal.AiAnalysisStatsService;
import com.bablsoft.accessflow.ai.internal.BadAiAnalysisStatsQueryException;
import com.bablsoft.accessflow.core.api.AiAnalysisIssueCategoryView;
import com.bablsoft.accessflow.core.api.AiAnalysisRiskScoreBucketView;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsRaw;
import com.bablsoft.accessflow.core.api.AiAnalysisSubmitterView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAiAnalysesControllerTest {

    private AiAnalysisStatsService statsService;
    private AdminAiAnalysesController controller;
    private final UUID organizationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        statsService = mock(AiAnalysisStatsService.class);
        controller = new AdminAiAnalysesController(statsService);
    }

    @Test
    void statsDelegatesToServiceAndMapsAllSeries() {
        var datasourceId = UUID.randomUUID();
        var submitterId = UUID.randomUUID();
        var from = Instant.parse("2026-04-01T00:00:00Z");
        var to = Instant.parse("2026-05-01T00:00:00Z");
        when(statsService.stats(organizationId, from, to, datasourceId))
                .thenReturn(new AiAnalysisStatsRaw(
                        List.of(new AiAnalysisRiskScoreBucketView(
                                LocalDate.of(2026, 4, 15), new BigDecimal("48"), 4L, 3L)),
                        List.of(new AiAnalysisIssueCategoryView("performance", 9L)),
                        List.of(new AiAnalysisSubmitterView(
                                submitterId, "a@x.test", "Alice", 5L))));

        var response = controller.stats(from, to, datasourceId, organizationId);

        verify(statsService).stats(organizationId, from, to, datasourceId);
        assertThat(response.riskScoreOverTime())
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.date()).isEqualTo(LocalDate.of(2026, 4, 15));
                    assertThat(p.successAvgRiskScore()).isEqualByComparingTo(new BigDecimal("48"));
                    assertThat(p.totalCount()).isEqualTo(4L);
                    assertThat(p.successCount()).isEqualTo(3L);
                });
        assertThat(response.topIssueCategories())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.category()).isEqualTo("performance");
                    assertThat(c.count()).isEqualTo(9L);
                });
        assertThat(response.topSubmitters())
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.userId()).isEqualTo(submitterId);
                    assertThat(s.email()).isEqualTo("a@x.test");
                    assertThat(s.displayName()).isEqualTo("Alice");
                    assertThat(s.count()).isEqualTo(5L);
                });
    }

    @Test
    void statsPropagatesBadQueryException() {
        when(statsService.stats(organizationId, null, null, null))
                .thenThrow(new BadAiAnalysisStatsQueryException("error.ai_analysis_stats.invalid_range"));

        assertThatThrownBy(() -> controller.stats(null, null, null, organizationId))
                .isInstanceOf(BadAiAnalysisStatsQueryException.class);
    }

    @Test
    void statsPassesNullDatasourceIdThroughForUnfilteredQuery() {
        when(statsService.stats(organizationId, null, null, null))
                .thenReturn(new AiAnalysisStatsRaw(List.of(), List.of(), List.of()));

        var response = controller.stats(null, null, null, organizationId);

        assertThat(response.riskScoreOverTime()).isEmpty();
        assertThat(response.topIssueCategories()).isEmpty();
        assertThat(response.topSubmitters()).isEmpty();
    }
}
