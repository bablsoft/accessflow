package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAnalysisStatsRawTest {

    @Test
    void nullListsAreNormalizedToEmpty() {
        var raw = new AiAnalysisStatsRaw(null, null, null);

        assertThat(raw.riskScoreOverTime()).isEmpty();
        assertThat(raw.topIssueCategories()).isEmpty();
        assertThat(raw.topSubmitters()).isEmpty();
    }

    @Test
    void listsAreDefensivelyCopied() {
        var bucket = new AiAnalysisRiskScoreBucketView(
                LocalDate.of(2026, 1, 1), new BigDecimal("50"), 1L, 1L);
        var category = new AiAnalysisIssueCategoryView("performance", 7L);
        var submitter = new AiAnalysisSubmitterView(
                UUID.randomUUID(), "a@example.test", "Alice", 3L);

        var raw = new AiAnalysisStatsRaw(List.of(bucket), List.of(category), List.of(submitter));

        // Returned lists must be unmodifiable copies, so mutation attempts fail.
        assertThatThrownBy(() -> raw.riskScoreOverTime().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> raw.topIssueCategories().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> raw.topSubmitters().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
