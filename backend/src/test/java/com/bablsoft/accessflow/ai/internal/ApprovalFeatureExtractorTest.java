package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.ApprovalOutcomeSample;
import com.bablsoft.accessflow.core.api.ApprovalRateCounts;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ApprovalFeatureExtractorTest {

    private static final double TOLERANCE = 1e-9;

    /** 22:15 UTC — deliberately off-hours, so the golden vector exercises that branch. */
    private static final Instant CREATED_AT = Instant.parse("2026-03-04T22:15:00Z");

    private static final ApprovalRateCounts SUBMITTER_COUNTS = new ApprovalRateCounts(10, 8);
    private static final ApprovalRateCounts DATASOURCE_COUNTS = new ApprovalRateCounts(3, 1);

    private final ApprovalFeatureExtractor extractor = new ApprovalFeatureExtractor();

    // --- input helpers -----------------------------------------------------------------------

    private static ApprovalFeatureInput full() {
        return new ApprovalFeatureInput(
                QueryType.UPDATE, true, CREATED_AT,
                72, RiskLevel.HIGH, 3, false,
                999L, 9L, 99.0, "Seq Scan", false);
    }

    private static ApprovalFeatureInput aiMissing() {
        return new ApprovalFeatureInput(
                QueryType.UPDATE, true, CREATED_AT,
                null, null, null, true,
                999L, 9L, 99.0, "Seq Scan", false);
    }

    private static ApprovalFeatureInput estimateMissing() {
        return new ApprovalFeatureInput(
                QueryType.UPDATE, true, CREATED_AT,
                72, RiskLevel.HIGH, 3, false,
                null, null, null, null, true);
    }

    private static ApprovalFeatureInput withRiskLevel(RiskLevel level) {
        return new ApprovalFeatureInput(
                QueryType.SELECT, false, CREATED_AT,
                50, level, 0, false,
                null, null, null, null, true);
    }

    private static ApprovalFeatureInput withQueryType(QueryType type) {
        return new ApprovalFeatureInput(
                type, false, CREATED_AT,
                null, null, null, true,
                null, null, null, null, true);
    }

    private static ApprovalFeatureInput withScanType(String scanType) {
        return new ApprovalFeatureInput(
                QueryType.SELECT, false, CREATED_AT,
                null, null, null, true,
                1L, 1L, 1.0, scanType, false);
    }

    private static ApprovalFeatureInput withCreatedAt(Instant createdAt) {
        return new ApprovalFeatureInput(
                QueryType.SELECT, false, createdAt,
                null, null, null, true,
                null, null, null, null, true);
    }

    private static ApprovalFeatureInput withEstimate(Long rows, Long affected, Double cost) {
        return new ApprovalFeatureInput(
                QueryType.SELECT, false, CREATED_AT,
                null, null, null, true,
                rows, affected, cost, null, false);
    }

    private static ApprovalFeatureInput allNulls() {
        return new ApprovalFeatureInput(
                null, false, null,
                null, null, null, false,
                null, null, null, null, false);
    }

    private ApprovalFeatureVector extract(ApprovalFeatureInput input) {
        return extractor.extract(input, SUBMITTER_COUNTS, DATASOURCE_COUNTS);
    }

    private static double feature(ApprovalFeatureVector vector, String name) {
        return vector.values()[ApprovalFeatureVector.FEATURE_SCHEMA_V1.indexOf(name)];
    }

    // --- golden vector -----------------------------------------------------------------------

    @Test
    void extractsEveryFeatureFromAFullyPopulatedInput() {
        var vector = extract(full());

        assertThat(vector.values()).containsExactly(new double[]{
                0.72,   // risk_score
                0.0,    // risk_level_LOW
                0.0,    // risk_level_MEDIUM
                1.0,    // risk_level_HIGH
                0.0,    // risk_level_CRITICAL
                3.0,    // issue_count
                0.0,    // ai_missing
                3.0,    // estimated_rows_log10   = log10(1000)
                1.0,    // affected_row_count_log10 = log10(10)
                2.0,    // estimated_cost_log10   = log10(100)
                1.0,    // seq_scan
                0.0,    // estimate_missing
                0.0,    // query_type_SELECT
                0.0,    // query_type_INSERT
                1.0,    // query_type_UPDATE
                0.0,    // query_type_DELETE
                0.0,    // query_type_DDL
                1.0,    // transactional
                1.0,    // off_hours
                0.75,   // submitter_approval_rate  = 9/12
                0.4,    // datasource_approval_rate = 2/5
        }, within(TOLERANCE));
    }

    @Test
    void vectorLengthAlwaysMatchesFeatureSchemaV1() {
        assertThat(extract(full()).values())
                .hasSize(ApprovalFeatureVector.FEATURE_SCHEMA_V1.size());
        assertThat(extract(allNulls()).values())
                .hasSize(ApprovalFeatureVector.FEATURE_SCHEMA_V1.size());
    }

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> extractor.extract(
                (ApprovalFeatureInput) null, SUBMITTER_COUNTS, DATASOURCE_COUNTS))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("input");
    }

    // --- AI analysis features ----------------------------------------------------------------

    @Test
    void aiPresentScalesRiskScoreByOneHundred() {
        assertThat(feature(extract(full()), "risk_score")).isCloseTo(0.72, within(TOLERANCE));
        assertThat(feature(extract(full()), "ai_missing")).isZero();
    }

    @Test
    void aiMissingZeroFillsRiskFeaturesAndSetsTheFlag() {
        var vector = extract(aiMissing());

        assertThat(feature(vector, "ai_missing")).isEqualTo(1.0);
        assertThat(feature(vector, "risk_score")).isZero();
        assertThat(feature(vector, "issue_count")).isZero();
        assertThat(feature(vector, "risk_level_LOW")).isZero();
        assertThat(feature(vector, "risk_level_MEDIUM")).isZero();
        assertThat(feature(vector, "risk_level_HIGH")).isZero();
        assertThat(feature(vector, "risk_level_CRITICAL")).isZero();
    }

    @ParameterizedTest
    @EnumSource(RiskLevel.class)
    void riskLevelOneHotSetsExactlyOnePosition(RiskLevel level) {
        var vector = extract(withRiskLevel(level));

        for (RiskLevel candidate : RiskLevel.values()) {
            assertThat(feature(vector, "risk_level_" + candidate.name()))
                    .as("risk_level_%s", candidate)
                    .isEqualTo(candidate == level ? 1.0 : 0.0);
        }
    }

    @Test
    void nullRiskLevelLeavesEveryRiskLevelPositionZero() {
        var vector = extract(withRiskLevel(null));

        for (RiskLevel candidate : RiskLevel.values()) {
            assertThat(feature(vector, "risk_level_" + candidate.name())).isZero();
        }
        // The risk score is still carried — only the one-hot block is unknown.
        assertThat(feature(vector, "risk_score")).isCloseTo(0.5, within(TOLERANCE));
    }

    @Test
    void issueCountIsCarriedRaw() {
        assertThat(feature(extract(full()), "issue_count")).isEqualTo(3.0);
        assertThat(feature(extract(withRiskLevel(RiskLevel.LOW)), "issue_count")).isZero();
    }

    // --- cost-estimate features ---------------------------------------------------------------

    @Test
    void estimateMissingZeroFillsEstimateFeaturesAndSetsTheFlag() {
        var vector = extract(estimateMissing());

        assertThat(feature(vector, "estimate_missing")).isEqualTo(1.0);
        assertThat(feature(vector, "estimated_rows_log10")).isZero();
        assertThat(feature(vector, "affected_row_count_log10")).isZero();
        assertThat(feature(vector, "estimated_cost_log10")).isZero();
        assertThat(feature(vector, "seq_scan")).isZero();
    }

    @Test
    void anAbsentEstimateChangesOnlyTheEstimateBlock() {
        // core collapses "no row", "unsupported engine" and "failed" into estimateMissing=true, so
        // all three reach the extractor identically; what must hold here is that losing the
        // estimate zero-fills its own five features and touches nothing else.
        var present = extract(full()).values();
        var absent = extract(estimateMissing()).values();

        for (int i = 0; i < present.length; i++) {
            String name = ApprovalFeatureVector.FEATURE_SCHEMA_V1.get(i);
            boolean estimateFeature = name.endsWith("_log10")
                    || "seq_scan".equals(name)
                    || "estimate_missing".equals(name);
            if (!estimateFeature) {
                assertThat(absent[i]).as(name).isEqualTo(present[i]);
            }
        }
        assertThat(feature(extract(estimateMissing()), "estimate_missing")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @CsvSource({"0, 0.0", "9, 1.0", "999, 3.0", "99999, 5.0"})
    void logFeaturesUseLog10OfOnePlusValue(long rows, double expected) {
        var vector = extract(withEstimate(rows, rows, (double) rows));

        assertThat(feature(vector, "estimated_rows_log10")).isCloseTo(expected, within(TOLERANCE));
        assertThat(feature(vector, "affected_row_count_log10")).isCloseTo(expected, within(TOLERANCE));
        assertThat(feature(vector, "estimated_cost_log10")).isCloseTo(expected, within(TOLERANCE));
    }

    @Test
    void negativeEstimateValuesClampToZero() {
        var vector = extract(withEstimate(-5L, -1L, -0.5));

        assertThat(feature(vector, "estimated_rows_log10")).isZero();
        assertThat(feature(vector, "affected_row_count_log10")).isZero();
        assertThat(feature(vector, "estimated_cost_log10")).isZero();
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonFiniteEstimatedCostClampsToZero(double cost) {
        // A double precision column accepts NaN/Infinity, so log10p must reject them explicitly.
        assertThat(feature(extract(withEstimate(1L, 1L, cost)), "estimated_cost_log10")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Seq Scan", "Parallel Seq Scan", "  seq scan  ", "SEQ SCAN", "Parallel  Seq  Scan",
            "ALL", "all", " All ",
            "TABLE ACCESS FULL", "TABLE ACCESS STORAGE FULL", "MAT_VIEW ACCESS FULL",
            "Table Scan", "TableScan",
            "COLLSCAN", "PrimaryScan", "PrimaryScan3",
            "AllNodesScan", "NodeByLabelScan"})
    void fullScanOperationsAreDetected(String scanType) {
        assertThat(feature(extract(withScanType(scanType)), "seq_scan")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Index Scan", "Index Only Scan", "Bitmap Heap Scan",
            "Clustered Index Scan", "Index Seek",
            "IXSCAN", "IndexScan3", "FETCH",
            "INDEX FULL SCAN", "INDEX FAST FULL SCAN", "INDEX RANGE SCAN",
            "TABLE ACCESS BY INDEX ROWID",
            "ref", "range", "eq_ref", "index", "index_merge", "const",
            "query_block", "Sequence", "ProduceResults", "Aggregate"})
    void indexAndNonScanOperationsAreNotFullScans(String scanType) {
        assertThat(feature(extract(withScanType(scanType)), "seq_scan")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM tablescan_log",
            "SELECT * FROM users -- table scan",
            "UPDATE collscan_audit SET x = 1",
            "SELECT all_orders.id FROM all_orders"})
    void statementTextInScanTypeIsNotAFullScan(String scanType) {
        // A SQL Server SELECT roots at the statement text (SqlServerDryRunPlanner), so scan_type
        // can carry user SQL. Whole-name matching is what keeps a table called tablescan_log from
        // being read as a sequential scan.
        assertThat(feature(extract(withScanType(scanType)), "seq_scan")).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void nullOrBlankScanTypeIsNotASeqScan(String scanType) {
        assertThat(feature(extract(withScanType(scanType)), "seq_scan")).isZero();
    }

    // --- query-request features ---------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = QueryType.class, names = {"SELECT", "INSERT", "UPDATE", "DELETE", "DDL"})
    void queryTypeOneHotSetsExactlyOnePosition(QueryType type) {
        var vector = extract(withQueryType(type));

        for (QueryType candidate : QueryType.values()) {
            if (candidate == QueryType.OTHER) {
                continue;
            }
            assertThat(feature(vector, "query_type_" + candidate.name()))
                    .as("query_type_%s", candidate)
                    .isEqualTo(candidate == type ? 1.0 : 0.0);
        }
    }

    @Test
    void queryTypeOtherIsTheReferenceCategoryAndSetsNoOneHot() {
        assertNoQueryTypeOneHot(extract(withQueryType(QueryType.OTHER)));
    }

    @Test
    void nullQueryTypeSetsNoOneHot() {
        assertNoQueryTypeOneHot(extract(withQueryType(null)));
    }

    private static void assertNoQueryTypeOneHot(ApprovalFeatureVector vector) {
        for (QueryType candidate : QueryType.values()) {
            if (candidate == QueryType.OTHER) {
                continue;
            }
            assertThat(feature(vector, "query_type_" + candidate.name())).isZero();
        }
    }

    @Test
    void transactionalMapsToOneAndNonTransactionalToZero() {
        assertThat(feature(extract(full()), "transactional")).isEqualTo(1.0);
        assertThat(feature(extract(withQueryType(QueryType.SELECT)), "transactional")).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "2026-03-04T00:00:00Z, 1.0",
            "2026-03-04T07:59:59Z, 1.0",
            "2026-03-04T08:00:00Z, 0.0",
            "2026-03-04T12:00:00Z, 0.0",
            "2026-03-04T17:59:59Z, 0.0",
            "2026-03-04T18:00:00Z, 1.0",
            "2026-03-04T23:59:59Z, 1.0"})
    void offHoursIsOutsideEightToEighteenUtc(String createdAt, double expected) {
        assertThat(feature(extract(withCreatedAt(Instant.parse(createdAt))), "off_hours"))
                .isEqualTo(expected);
    }

    @Test
    void nullCreatedAtYieldsZeroOffHours() {
        assertThat(feature(extract(withCreatedAt(null)), "off_hours")).isZero();
    }

    // --- historical rate features -------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "10, 8, 0.75",
            "3, 1, 0.4",
            "1, 1, 0.6666666666666666",
            "0, 0, 0.5"})
    void approvalRatesAreLaplaceSmoothed(long decided, long approved, double expected) {
        var counts = new ApprovalRateCounts(decided, approved);
        var vector = extractor.extract(allNulls(), counts, counts);

        assertThat(feature(vector, "submitter_approval_rate")).isCloseTo(expected, within(TOLERANCE));
        assertThat(feature(vector, "datasource_approval_rate")).isCloseTo(expected, within(TOLERANCE));
    }

    @Test
    void nullRateCountsYieldOneHalf() {
        var vector = extractor.extract(allNulls(), null, null);

        assertThat(feature(vector, "submitter_approval_rate")).isCloseTo(0.5, within(TOLERANCE));
        assertThat(feature(vector, "datasource_approval_rate")).isCloseTo(0.5, within(TOLERANCE));
    }

    @Test
    void submitterAndDatasourceRatesAreIndependent() {
        var vector = extract(allNulls());

        assertThat(feature(vector, "submitter_approval_rate")).isCloseTo(0.75, within(TOLERANCE));
        assertThat(feature(vector, "datasource_approval_rate")).isCloseTo(0.4, within(TOLERANCE));
    }

    // --- missing-value policy + adapter parity ------------------------------------------------

    @Test
    void allNullInputProducesNoNaNOrInfinity() {
        var values = extractor.extract(allNulls(), null, null).values();

        for (int i = 0; i < values.length; i++) {
            assertThat(Double.isFinite(values[i]))
                    .as("%s is finite", ApprovalFeatureVector.FEATURE_SCHEMA_V1.get(i))
                    .isTrue();
        }
    }

    @Test
    void sampleAdapterProducesTheSameVectorAsTheInputOverload() {
        var sample = new ApprovalOutcomeSample(
                UUID.randomUUID(), QueryType.UPDATE, true, CREATED_AT,
                UUID.randomUUID(), UUID.randomUUID(),
                72, RiskLevel.HIGH, 3, false,
                999L, 9L, 99.0, "Seq Scan", false,
                true);

        var fromSample = extractor.extract(sample, SUBMITTER_COUNTS, DATASOURCE_COUNTS);

        assertThat(fromSample).isEqualTo(extract(full()));
    }

    @Test
    void theApprovedLabelIsNotAFeature() {
        var approved = new ApprovalOutcomeSample(
                UUID.randomUUID(), QueryType.SELECT, false, CREATED_AT,
                UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, true, null, null, null, null, true,
                true);
        var rejected = new ApprovalOutcomeSample(
                approved.queryRequestId(), QueryType.SELECT, false, CREATED_AT,
                approved.submitterId(), approved.datasourceId(),
                null, null, null, true, null, null, null, null, true,
                false);

        assertThat(extractor.extract(approved, SUBMITTER_COUNTS, DATASOURCE_COUNTS))
                .isEqualTo(extractor.extract(rejected, SUBMITTER_COUNTS, DATASOURCE_COUNTS));
    }
}
