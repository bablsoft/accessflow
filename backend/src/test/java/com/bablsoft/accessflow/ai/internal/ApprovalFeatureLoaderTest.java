package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.ApprovalPredictionProperties;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.ApprovalOutcomeHistoryLookupService;
import com.bablsoft.accessflow.core.api.ApprovalRateCounts;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalFeatureLoaderTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-06T10:00:00Z");
    private static final Duration LOOKBACK = Duration.ofDays(180);

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ApprovalOutcomeHistoryLookupService historyLookupService;

    private final UUID queryId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    ApprovalFeatureLoader loader;

    @BeforeEach
    void setUp() {
        var properties = new ApprovalPredictionProperties(true, Duration.ofDays(1), 50, LOOKBACK,
                0.2, 0.65, 0.01, 500);
        loader = new ApprovalFeatureLoader(queryRequestLookupService, historyLookupService,
                new ApprovalFeatureExtractor(), properties, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private QueryRequestSnapshot snapshot(boolean transactional) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, transactional, QueryStatus.PENDING_REVIEW, null,
                null, null, false);
    }

    private static QueryDetailView.AiAnalysisDetail analysis(String issuesJson, boolean failed) {
        return new QueryDetailView.AiAnalysisDetail(UUID.randomUUID(), RiskLevel.HIGH, 42,
                "summary", issuesJson, null, false, null, AiProviderType.OPENAI, "gpt-4o-mock",
                0, 0, failed, null);
    }

    private static QueryDetailView.CostEstimateDetail estimate(boolean supported, boolean failed) {
        return new QueryDetailView.CostEstimateDetail(UUID.randomUUID(), "postgresql",
                QueryType.SELECT, supported, 999L, 50L, "Seq Scan", 12.5, null, null, null,
                failed, null, null);
    }

    private QueryDetailView detail(QueryDetailView.AiAnalysisDetail ai,
                                   QueryDetailView.CostEstimateDetail costEstimate) {
        return new QueryDetailView(queryId, datasourceId, "ds", DbType.POSTGRESQL, organizationId,
                submitterId, "s@a.test", "Submitter", "SELECT 1", QueryType.SELECT,
                QueryStatus.PENDING_REVIEW, "why", ai, costEstimate, null, null, null, null, null,
                null, null, List.of(), null, SUBMITTED_AT, SUBMITTED_AT);
    }

    private void stubDetail(QueryDetailView.AiAnalysisDetail ai,
                           QueryDetailView.CostEstimateDetail costEstimate) {
        when(queryRequestLookupService.findDetailById(queryId, organizationId))
                .thenReturn(Optional.of(detail(ai, costEstimate)));
        when(historyLookupService.submitterCounts(organizationId, submitterId,
                NOW.minus(LOOKBACK))).thenReturn(new ApprovalRateCounts(8, 6));
        when(historyLookupService.datasourceCounts(organizationId, datasourceId,
                NOW.minus(LOOKBACK))).thenReturn(new ApprovalRateCounts(18, 9));
    }

    private static double feature(ApprovalFeatureVector vector, String name) {
        return vector.values()[ApprovalFeatureVector.FEATURE_SCHEMA_V1.indexOf(name)];
    }

    @Test
    void loadMapsEveryFeatureFromTheDetailView() {
        stubDetail(analysis("[{\"code\":\"A\"},{\"code\":\"B\"}]", false), estimate(true, false));

        var vector = loader.load(snapshot(false));

        assertThat(vector).isNotNull();
        assertThat(feature(vector, "risk_score")).isEqualTo(0.42);
        assertThat(feature(vector, "risk_level_HIGH")).isEqualTo(1.0);
        assertThat(feature(vector, "issue_count")).isEqualTo(2.0);
        assertThat(feature(vector, "ai_missing")).isZero();
        assertThat(feature(vector, "estimate_missing")).isZero();
        assertThat(feature(vector, "seq_scan")).isEqualTo(1.0);
        assertThat(feature(vector, "query_type_SELECT")).isEqualTo(1.0);
        assertThat(feature(vector, "transactional")).isZero();
        // 10:00Z is inside business hours, so off_hours must not fire.
        assertThat(feature(vector, "off_hours")).isZero();
        assertThat(feature(vector, "submitter_approval_rate")).isEqualTo(7.0 / 10.0);
        assertThat(feature(vector, "datasource_approval_rate")).isEqualTo(10.0 / 20.0);
    }

    @Test
    void loadDerivesTheRateWindowFromTheInjectedClockAndLookback() {
        stubDetail(analysis("[]", false), estimate(true, false));

        loader.load(snapshot(false));

        verify(historyLookupService).submitterCounts(organizationId, submitterId,
                Instant.parse("2026-02-07T12:00:00Z"));
        verify(historyLookupService).datasourceCounts(organizationId, datasourceId,
                Instant.parse("2026-02-07T12:00:00Z"));
    }

    @Test
    void loadReturnsNullWhenTheDetailViewIsGone() {
        when(queryRequestLookupService.findDetailById(queryId, organizationId))
                .thenReturn(Optional.empty());

        assertThat(loader.load(snapshot(false))).isNull();
    }

    @Test
    void loadFlagsAiMissingWhenNoAnalysisIsAttached() {
        stubDetail(null, estimate(true, false));

        var vector = loader.load(snapshot(false));

        assertThat(feature(vector, "ai_missing")).isEqualTo(1.0);
        assertThat(feature(vector, "risk_score")).isZero();
        assertThat(feature(vector, "risk_level_HIGH")).isZero();
        assertThat(feature(vector, "issue_count")).isZero();
    }

    @Test
    void loadFlagsAiMissingWhenTheAnalysisFailed() {
        stubDetail(analysis("[{\"code\":\"A\"}]", true), estimate(true, false));

        var vector = loader.load(snapshot(false));

        // A failed analysis carries a placeholder risk score; training nulls it out, so must we.
        assertThat(feature(vector, "ai_missing")).isEqualTo(1.0);
        assertThat(feature(vector, "risk_score")).isZero();
        assertThat(feature(vector, "issue_count")).isZero();
    }

    @Test
    void loadFlagsEstimateMissingWhenNoEstimateIsAttached() {
        stubDetail(analysis("[]", false), null);

        var vector = loader.load(snapshot(false));

        assertThat(feature(vector, "estimate_missing")).isEqualTo(1.0);
        assertThat(feature(vector, "estimated_rows_log10")).isZero();
        assertThat(feature(vector, "seq_scan")).isZero();
    }

    @Test
    void loadFlagsEstimateMissingWhenTheEngineDoesNotSupportPlans() {
        stubDetail(analysis("[]", false), estimate(false, false));

        var vector = loader.load(snapshot(false));

        assertThat(feature(vector, "estimate_missing")).isEqualTo(1.0);
        assertThat(feature(vector, "estimated_cost_log10")).isZero();
    }

    @Test
    void loadFlagsEstimateMissingWhenTheEstimateFailed() {
        stubDetail(analysis("[]", false), estimate(true, true));

        var vector = loader.load(snapshot(false));

        assertThat(feature(vector, "estimate_missing")).isEqualTo(1.0);
        assertThat(feature(vector, "affected_row_count_log10")).isZero();
    }

    @Test
    void loadCountsUnparseableIssuesJsonAsZero() {
        stubDetail(analysis("not json", false), estimate(true, false));

        var vector = loader.load(snapshot(false));

        assertThat(feature(vector, "issue_count")).isZero();
        assertThat(feature(vector, "ai_missing")).isZero();
    }

    @Test
    void loadCountsNullIssuesJsonAsZero() {
        stubDetail(analysis(null, false), estimate(true, false));

        assertThat(feature(loader.load(snapshot(false)), "issue_count")).isZero();
    }

    @Test
    void loadFallsBackToTheUninformativePriorWhenAnOrgHasNoDecidedHistory() {
        when(queryRequestLookupService.findDetailById(queryId, organizationId))
                .thenReturn(Optional.of(detail(analysis("[]", false), estimate(true, false))));
        when(historyLookupService.submitterCounts(organizationId, submitterId,
                NOW.minus(LOOKBACK))).thenReturn(null);
        when(historyLookupService.datasourceCounts(organizationId, datasourceId,
                NOW.minus(LOOKBACK))).thenReturn(new ApprovalRateCounts(0, 0));

        var vector = loader.load(snapshot(false));

        assertThat(feature(vector, "submitter_approval_rate")).isEqualTo(0.5);
        assertThat(feature(vector, "datasource_approval_rate")).isEqualTo(0.5);
    }

    @Test
    void loadReadsTransactionalFromTheSnapshotNotTheDetailView() {
        stubDetail(analysis("[]", false), estimate(true, false));

        assertThat(feature(loader.load(snapshot(true)), "transactional")).isEqualTo(1.0);
    }
}
