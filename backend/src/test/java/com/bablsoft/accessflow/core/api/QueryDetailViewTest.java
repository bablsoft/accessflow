package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record grew two async-enrichment blocks after it shipped, each behind a backward-compatible
 * constructor. These pin the delegation chain: 24-arg (pre-AF-624) → 25-arg (pre-AF-645) →
 * canonical, defaulting the newer components to absent rather than shifting them.
 */
class QueryDetailViewTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T09:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-01T09:05:00Z");

    private final UUID id = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();

    @Test
    void preCostEstimateConstructorDefaultsBothEnrichmentBlocksToAbsent() {
        var view = new QueryDetailView(id, datasourceId, "ds", DbType.POSTGRESQL, organizationId,
                submitterId, "s@a.test", "Submitter", "SELECT 1", QueryType.SELECT,
                QueryStatus.PENDING_REVIEW, "why", null, 3L, 12, null, null, null, "plan", 24,
                List.of(), null, CREATED_AT, UPDATED_AT);

        assertThat(view.costEstimate()).isNull();
        assertThat(view.approvalPrediction()).isNull();
        assertThat(view.rowsAffected()).isEqualTo(3L);
        assertThat(view.durationMs()).isEqualTo(12);
        assertThat(view.reviewPlanName()).isEqualTo("plan");
        assertThat(view.approvalTimeoutHours()).isEqualTo(24);
        assertThat(view.createdAt()).isEqualTo(CREATED_AT);
        assertThat(view.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void preApprovalPredictionConstructorKeepsCostEstimateAndDefaultsPrediction() {
        var estimate = new QueryDetailView.CostEstimateDetail(UUID.randomUUID(), "postgresql",
                QueryType.SELECT, true, 999L, null, "Seq Scan", 12.5, null, null, null, false,
                null, 8);

        var view = new QueryDetailView(id, datasourceId, "ds", DbType.POSTGRESQL, organizationId,
                submitterId, "s@a.test", "Submitter", "SELECT 1", QueryType.SELECT,
                QueryStatus.PENDING_REVIEW, "why", null, estimate, 3L, 12, null, null, null,
                "plan", 24, List.of(), null, CREATED_AT, UPDATED_AT);

        assertThat(view.costEstimate()).isSameAs(estimate);
        assertThat(view.approvalPrediction()).isNull();
        assertThat(view.rowsAffected()).isEqualTo(3L);
        assertThat(view.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void canonicalConstructorCarriesTheApprovalPrediction() {
        var prediction = new QueryDetailView.ApprovalPredictionDetail(UUID.randomUUID(), 0.78,
                false, null, false, CREATED_AT);

        var view = new QueryDetailView(id, datasourceId, "ds", DbType.POSTGRESQL, organizationId,
                submitterId, "s@a.test", "Submitter", "SELECT 1", QueryType.SELECT,
                QueryStatus.PENDING_REVIEW, "why", null, null, prediction, 3L, 12, null, null,
                null, "plan", 24, List.of(), null, CREATED_AT, UPDATED_AT);

        assertThat(view.approvalPrediction()).isSameAs(prediction);
        assertThat(view.approvalPrediction().probability()).isEqualTo(0.78);
        assertThat(view.approvalPrediction().skipped()).isFalse();
        assertThat(view.approvalPrediction().failed()).isFalse();
    }
}
