package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryCorpusRow;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConditionContextFactoryTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock SqlParserService sqlParserService;
    @Mock UserQueryService userQueryService;
    @Mock UserGroupService userGroupService;
    @Mock BehaviorAnomalyLookupService behaviorAnomalyLookupService;
    @Mock QueryEstimateLookupService queryEstimateLookupService;

    private ConditionContextFactory factory;

    private final UUID queryId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-14T09:12:00Z");

    @BeforeEach
    void setUp() {
        factory = new ConditionContextFactory(queryRequestLookupService, sqlParserService,
                userQueryService, userGroupService, behaviorAnomalyLookupService,
                queryEstimateLookupService);
        when(sqlParserService.parse(any())).thenReturn(new SqlParseResult(QueryType.SELECT, false,
                List.of("SELECT 1"), Set.of("public.orders"), true, false));
        when(userQueryService.findById(submitterId)).thenReturn(Optional.empty());
        when(userGroupService.findGroupIdsForUser(submitterId)).thenReturn(List.of());
        when(queryRequestLookupService.findLastApprovalInstant(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private com.bablsoft.accessflow.core.api.QueryEstimateSnapshot estimate(
            Long estimatedRows, Long affectedRowCount, String scanType, boolean failed) {
        return new com.bablsoft.accessflow.core.api.QueryEstimateSnapshot(UUID.randomUUID(),
                queryId, "postgresql", QueryType.SELECT, true, estimatedRows, affectedRowCount,
                scanType, null, null, null, null, failed, null, 5, SUBMITTED_AT);
    }

    private QueryCorpusRow row(RiskLevel level, Integer score) {
        return new QueryCorpusRow(queryId, orgId, datasourceId, "prod", DbType.POSTGRESQL,
                submitterId, "a@x.io", "Ada", "SELECT * FROM orders", QueryType.SELECT,
                QueryStatus.EXECUTED, false, level, score, false,
                "10.0.0.7", "psql/16", true, SUBMITTED_AT);
    }

    @Test
    void historicalRowIsEvaluatedAtItsOwnSubmissionInstantNotNow() {
        var context = factory.forHistoricalRow(row(RiskLevel.HIGH, 80), ZoneId.of("UTC"));

        // time_of_day / day_of_week must replay against the moment the query was submitted.
        assertThat(context.evaluatedAt().getHour()).isEqualTo(9);
        assertThat(context.evaluatedAt().getMinute()).isEqualTo(12);
        assertThat(context.evaluatedAt().toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026, 7, 14));
    }

    @Test
    void historicalRowConvertsTheInstantIntoTheRequestedZone() {
        var context = factory.forHistoricalRow(row(RiskLevel.HIGH, 80), ZoneId.of("Asia/Yerevan"));
        assertThat(context.evaluatedAt().getHour()).isEqualTo(13);
    }

    @Test
    void historicalRowCarriesTheClientContextAndParsedSignals() {
        var context = factory.forHistoricalRow(row(RiskLevel.HIGH, 80), ZoneId.of("UTC"));

        assertThat(context.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(context.referencedTables()).containsExactly("public.orders");
        assertThat(context.hasWhereClause()).isTrue();
        assertThat(context.hasLimitClause()).isFalse();
        assertThat(context.requesterIpAddress()).isEqualTo("10.0.0.7");
        assertThat(context.requesterUserAgent()).isEqualTo("psql/16");
        assertThat(context.ciCdOrigin()).isTrue();
        assertThat(context.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(context.riskScore()).isEqualTo(80);
        assertThat(context.hasRiskSignal()).isTrue();
    }

    @Test
    void historicalRowWithNoAiVerdictHasNoRiskSignalSoRiskPoliciesFailClosed() {
        var context = factory.forHistoricalRow(row(null, null), ZoneId.of("UTC"));

        assertThat(context.riskLevel()).isNull();
        assertThat(context.riskScore()).isEqualTo(-1);
        assertThat(context.hasRiskSignal()).isFalse();
    }

    @Test
    void historicalRowForcesTheAnomalySignalOffBecauseItCannotBeReconstructed() {
        when(behaviorAnomalyLookupService.hasActiveAnomaly(any(), any(), any())).thenReturn(true);

        var context = factory.forHistoricalRow(row(RiskLevel.LOW, 10), ZoneId.of("UTC"));

        // Today's open anomalies say nothing about a query submitted months ago; the caller reports
        // this as a caveat rather than letting a stale signal move the result.
        assertThat(context.anomalyActive()).isFalse();
    }

    @Test
    void historicalRowReplaysThePersistedCostEstimate() {
        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(estimate(5_000L, null, "Seq Scan", false)));

        var context = factory.forHistoricalRow(row(RiskLevel.LOW, 10), ZoneId.of("UTC"));

        // The AF-624 estimate is a persisted per-query fact. Dropping it would make an
        // estimated_rows policy simulate as matching nothing — a false all-clear.
        assertThat(context.hasEstimateSignal()).isTrue();
        assertThat(context.estimatedRows()).isEqualTo(5_000L);
        assertThat(context.scanType()).isEqualTo("Seq Scan");
    }

    @Test
    void historicalRowPrefersTheExactAffectedRowCountOverThePlanEstimate() {
        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(estimate(5_000L, 12L, "Index Scan", false)));

        assertThat(factory.forHistoricalRow(row(RiskLevel.LOW, 10), ZoneId.of("UTC")).estimatedRows())
                .isEqualTo(12L);
    }

    @Test
    void aFailedEstimateLeavesTheSignalAbsentSoThoseOperandsFailClosed() {
        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(estimate(5_000L, null, "Seq Scan", true)));

        var context = factory.forHistoricalRow(row(RiskLevel.LOW, 10), ZoneId.of("UTC"));

        assertThat(context.hasEstimateSignal()).isFalse();
        assertThat(context.scanType()).isNull();
    }

    @Test
    void anAbsentEstimateLeavesTheSignalAbsent() {
        var context = factory.forHistoricalRow(row(RiskLevel.LOW, 10), ZoneId.of("UTC"));

        assertThat(context.hasEstimateSignal()).isFalse();
    }

    @Test
    void historicalRowMeasuresRecencyFromTheSubmissionInstantAndIgnoresLaterApprovals() {
        when(queryRequestLookupService.findLastApprovalInstant(eq(orgId), eq(submitterId),
                eq(datasourceId), eq(queryId)))
                .thenReturn(Optional.of(SUBMITTED_AT.minusSeconds(1800)));

        var context = factory.forHistoricalRow(row(RiskLevel.LOW, 10), ZoneId.of("UTC"));

        assertThat(context.minutesSinceLastApproval()).isEqualTo(30);
    }

    @Test
    void anApprovalAfterTheHistoricalRowIsNotCountedAsRecency() {
        when(queryRequestLookupService.findLastApprovalInstant(any(), any(), any(), any()))
                .thenReturn(Optional.of(SUBMITTED_AT.plusSeconds(3600)));

        var context = factory.forHistoricalRow(row(RiskLevel.LOW, 10), ZoneId.of("UTC"));

        // Leaking a later approval into the past would make the recency operand match on evidence
        // that did not exist when the query ran.
        assertThat(context.minutesSinceLastApproval()).isNull();
    }

    @Test
    void anUnparseableHistoricalQueryDegradesToEmptyTableSignals() {
        when(sqlParserService.parse(any())).thenThrow(new IllegalStateException("bad sql"));

        var context = factory.forHistoricalRow(row(RiskLevel.LOW, 10), ZoneId.of("UTC"));

        assertThat(context.referencedTables()).isEmpty();
        assertThat(context.hasWhereClause()).isFalse();
        assertThat(context.hasLimitClause()).isFalse();
    }
}
