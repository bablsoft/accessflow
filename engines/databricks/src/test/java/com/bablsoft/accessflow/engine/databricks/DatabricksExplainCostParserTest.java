package com.bablsoft.accessflow.engine.databricks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabricksExplainCostParserTest {

    private static final String PLAN = """
            == Optimized Logical Plan ==
            Aggregate [tenant#12], [tenant#12, count(1) AS cnt#40L], Statistics(sizeInBytes=4.2 GiB, rowCount=1.23E+5)
            +- Filter (isnotnull(tenant#12) AND (tenant#12 = acme)), Statistics(sizeInBytes=1447.3 KiB, rowCount=100)
               +- Relation main.sales.orders[id#11L,tenant#12] parquet, Statistics(sizeInBytes=12.0 MiB, rowCount=5000)

            == Physical Plan ==
            AdaptiveSparkPlan isFinalPlan=false
            """;

    @Test
    void parsesTopLevelStatisticsFromOptimizedLogicalPlan() {
        var estimate = DatabricksExplainCostParser.parse(PLAN);
        assertThat(estimate.sizeInBytes()).isEqualTo(Math.round(4.2 * (1L << 30)));
        assertThat(estimate.rowCount()).isEqualTo(123_000L);
    }

    @Test
    void parsesPlainBytesAndIntegerRowCount() {
        var estimate = DatabricksExplainCostParser.parse(
                "Relation t[id#1] parquet, Statistics(sizeInBytes=1536 B, rowCount=42)");
        assertThat(estimate.sizeInBytes()).isEqualTo(1536L);
        assertThat(estimate.rowCount()).isEqualTo(42L);
    }

    @Test
    void handlesMissingRowCount() {
        var estimate = DatabricksExplainCostParser.parse(
                "Filter x, Statistics(sizeInBytes=2.0 KiB)");
        assertThat(estimate.sizeInBytes()).isEqualTo(2048L);
        assertThat(estimate.rowCount()).isNull();
    }

    @Test
    void unknownSizeSentinelDegradesToNull() {
        // Catalyst prints spark.sql.defaultSizeInBytes (= Long.MaxValue, "8.0 EiB") for
        // relations whose size it cannot determine — that is "unknown", not an estimate.
        assertThat(DatabricksExplainCostParser.parse(
                "Relation jdbc_t[id#1], Statistics(sizeInBytes=8.0 EiB)").sizeInBytes()).isNull();
        assertThat(DatabricksExplainCostParser.parse(
                "Join Inner, Statistics(sizeInBytes=9.9E+10 EiB)").sizeInBytes()).isNull();
    }

    @Test
    void appliesEveryBinaryUnitFactor() {
        assertThat(DatabricksExplainCostParser.parse(
                "F, Statistics(sizeInBytes=3.0 MiB)").sizeInBytes()).isEqualTo(3L << 20);
        assertThat(DatabricksExplainCostParser.parse(
                "F, Statistics(sizeInBytes=1.5 TiB)").sizeInBytes())
                .isEqualTo((long) (1.5 * (1L << 40)));
        assertThat(DatabricksExplainCostParser.parse(
                "F, Statistics(sizeInBytes=2.0 PiB)").sizeInBytes()).isEqualTo(2L << 50);
    }

    @Test
    void negativeValuesDegradeToNull() {
        var estimate = DatabricksExplainCostParser.parse(
                "F, Statistics(sizeInBytes=-1.0 KiB, rowCount=-5)");
        assertThat(estimate.sizeInBytes()).isNull();
        assertThat(estimate.rowCount()).isNull();
    }

    @Test
    void missingStatisticsYieldsNulls() {
        var estimate = DatabricksExplainCostParser.parse("== Physical Plan ==\nScan parquet");
        assertThat(estimate.sizeInBytes()).isNull();
        assertThat(estimate.rowCount()).isNull();
    }

    @Test
    void nullAndBlankTextYieldNulls() {
        assertThat(DatabricksExplainCostParser.parse(null).sizeInBytes()).isNull();
        assertThat(DatabricksExplainCostParser.parse("  ").rowCount()).isNull();
    }

    @Test
    void malformedNumbersDegradeToNullsInsteadOfThrowing() {
        var estimate = DatabricksExplainCostParser.parse(
                "Filter x, Statistics(sizeInBytes=-- KiB, rowCount=oops)");
        assertThat(estimate.sizeInBytes()).isNull();
        assertThat(estimate.rowCount()).isNull();
    }

    @Test
    void ignoresStatisticsBeforeTheOptimizedSection() {
        var text = """
                == Analyzed Logical Plan ==
                Filter y, Statistics(sizeInBytes=9.0 GiB, rowCount=9)
                == Optimized Logical Plan ==
                Filter x, Statistics(sizeInBytes=1.0 KiB, rowCount=1)
                """;
        var estimate = DatabricksExplainCostParser.parse(text);
        assertThat(estimate.sizeInBytes()).isEqualTo(1024L);
        assertThat(estimate.rowCount()).isEqualTo(1L);
    }
}
