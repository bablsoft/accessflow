package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.workflow.api.ReviewService.PendingReview;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PendingReviewItemTest {

    @Test
    void fromMapsAllFieldsWhenAiAnalysisPresent() {
        var queryId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var submitterId = UUID.randomUUID();
        var aiAnalysisId = UUID.randomUUID();
        var createdAt = Instant.parse("2025-01-15T10:00:00Z");
        var pending = new PendingReview(
                queryId, datasourceId, "Production",
                submitterId, "alice@example.com",
                "SELECT 1", QueryType.SELECT, "ticket-42",
                aiAnalysisId, RiskLevel.MEDIUM, 42, "Single-row UPDATE",
                0.78, 1, createdAt);

        var item = PendingReviewItem.from(pending);

        assertThat(item.id()).isEqualTo(queryId);
        assertThat(item.datasource())
                .isEqualTo(new PendingReviewItem.DatasourceSummary(datasourceId, "Production"));
        assertThat(item.submittedBy())
                .isEqualTo(new PendingReviewItem.SubmitterSummary(submitterId,
                        "alice@example.com"));
        assertThat(item.sqlText()).isEqualTo("SELECT 1");
        assertThat(item.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(item.justification()).isEqualTo("ticket-42");
        assertThat(item.aiAnalysis()).isEqualTo(new PendingReviewItem.AiAnalysisSummary(
                aiAnalysisId, RiskLevel.MEDIUM, 42, "Single-row UPDATE"));
        assertThat(item.approvalProbability()).isEqualTo(0.78);
        assertThat(item.currentStage()).isEqualTo(1);
        assertThat(item.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void fromOmitsAiAnalysisWhenAnalysisIdIsNull() {
        var pending = new PendingReview(
                UUID.randomUUID(), UUID.randomUUID(), "Staging",
                UUID.randomUUID(), "bob@example.com",
                "INSERT INTO t VALUES (1)", QueryType.INSERT, null,
                null, null, null, null,
                null, 2, Instant.parse("2025-01-15T10:00:00Z"));

        var item = PendingReviewItem.from(pending);

        assertThat(item.aiAnalysis()).isNull();
        assertThat(item.justification()).isNull();
        assertThat(item.currentStage()).isEqualTo(2);
        assertThat(item.queryType()).isEqualTo(QueryType.INSERT);
    }

    /** A skipped or failed prediction row carries no probability; the queue simply omits it. */
    @Test
    void fromKeepsApprovalProbabilityNullWhenNoPredictionScored() {
        var pending = new PendingReview(
                UUID.randomUUID(), UUID.randomUUID(), "Staging",
                UUID.randomUUID(), "bob@example.com",
                "SELECT 1", QueryType.SELECT, null,
                UUID.randomUUID(), RiskLevel.LOW, 5, "fine",
                null, 1, Instant.parse("2025-01-15T10:00:00Z"));

        var item = PendingReviewItem.from(pending);

        assertThat(item.approvalProbability()).isNull();
        assertThat(item.aiAnalysis()).isNotNull();
    }
}
