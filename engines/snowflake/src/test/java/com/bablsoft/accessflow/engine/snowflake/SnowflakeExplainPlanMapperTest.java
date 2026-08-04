package com.bablsoft.accessflow.engine.snowflake;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnowflakeExplainPlanMapperTest {

    private static final List<String> LABELS = List.of("step", "id", "parentOperators",
            "operation", "objects", "alias", "expressions", "partitionsTotal",
            "partitionsAssigned", "bytesAssigned");

    /** Mocked tabular-EXPLAIN result set: one values array per row, ordered like {@code LABELS}. */
    private static ResultSet explainResult(List<String> labels, List<List<Object>> rows)
            throws SQLException {
        var metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            when(metaData.getColumnLabel(i + 1)).thenReturn(labels.get(i));
        }
        var resultSet = mock(ResultSet.class);
        when(resultSet.getMetaData()).thenReturn(metaData);
        var cursor = new int[]{-1};
        when(resultSet.next()).thenAnswer(inv -> ++cursor[0] < rows.size());
        for (int i = 0; i < labels.size(); i++) {
            var column = i;
            when(resultSet.getObject(labels.get(i)))
                    .thenAnswer(inv -> rows.get(cursor[0]).get(column));
        }
        return resultSet;
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    @Test
    void mapsGlobalStatsBytesAndOperatorTree() throws SQLException {
        var resultSet = explainResult(LABELS, List.of(
                row(1, null, null, "GlobalStats", null, null, null, 3, 1, 1536),
                row(1, 0, "[]", "Result", null, null, "T.ID", null, null, null),
                row(1, 1, "[0]", "Filter", null, null, "T.TENANT = 'acme'", null, null, null),
                row(1, 2, "[1]", "TableScan", "DB.PUBLIC.ORDERS", null, "ID, TENANT", 3, 1,
                        1536)));

        var explained = SnowflakeExplainPlanMapper.map(resultSet);

        assertThat(explained.bytesAssigned()).isEqualTo(1536L);
        var root = explained.plan();
        assertThat(root.operation()).isEqualTo("Result");
        assertThat(root.children()).hasSize(1);
        var filter = root.children().getFirst();
        assertThat(filter.operation()).isEqualTo("Filter");
        assertThat(filter.detail()).isEqualTo("T.TENANT = 'acme'");
        var scan = filter.children().getFirst();
        assertThat(scan.operation()).isEqualTo("TableScan");
        assertThat(scan.target()).isEqualTo("DB.PUBLIC.ORDERS");
        assertThat(scan.detail()).isEqualTo("ID, TENANT · partitions 1/3 · bytes 1536");
        assertThat(explained.rawPlan()).contains("GlobalStats").contains("TableScan")
                .contains("bytes=1536").contains("partitions=1/3");
    }

    @Test
    void missingGlobalStatsYieldsNullBytesButKeepsPlan() throws SQLException {
        var resultSet = explainResult(LABELS, List.of(
                row(1, 0, "[]", "Result", null, null, null, null, null, null),
                row(1, 1, "[0]", "TableScan", "DB.PUBLIC.T", null, null, 2, 2, 4096)));

        var explained = SnowflakeExplainPlanMapper.map(resultSet);

        assertThat(explained.bytesAssigned()).isNull();
        assertThat(explained.plan().operation()).isEqualTo("Result");
        assertThat(explained.plan().children().getFirst().target()).isEqualTo("DB.PUBLIC.T");
    }

    @Test
    void fallsBackToSingleParentColumnWhenParentOperatorsAbsent() throws SQLException {
        var labels = List.of("id", "parent", "operation", "objects", "partitionsTotal",
                "partitionsAssigned", "bytesAssigned");
        var resultSet = explainResult(labels, List.of(
                row(0, null, "Result", null, null, null, null),
                row(1, 0, "TableScan", "DB.PUBLIC.T", 1, 1, 100)));

        var explained = SnowflakeExplainPlanMapper.map(resultSet);

        assertThat(explained.plan().operation()).isEqualTo("Result");
        assertThat(explained.plan().children().getFirst().operation()).isEqualTo("TableScan");
    }

    @Test
    void emptyResultYieldsNulls() throws SQLException {
        var explained = SnowflakeExplainPlanMapper.map(explainResult(LABELS, List.of()));
        assertThat(explained.plan()).isNull();
        assertThat(explained.bytesAssigned()).isNull();
        assertThat(explained.rawPlan()).isNull();
    }

    @Test
    void orphanOperatorsAttachUnderRootInsteadOfDisappearing() throws SQLException {
        var resultSet = explainResult(LABELS, List.of(
                row(1, 0, "[]", "Result", null, null, null, null, null, null),
                row(1, 5, "[9]", "TableScan", "DB.PUBLIC.T", null, null, null, null, null)));

        var explained = SnowflakeExplainPlanMapper.map(resultSet);

        assertThat(explained.plan().operation()).isEqualTo("Result");
        assertThat(explained.plan().children()).extracting(n -> n.operation())
                .containsExactly("TableScan");
    }

    @Test
    void outOfIntRangeIdsDegradeInsteadOfThrowing() throws SQLException {
        var resultSet = explainResult(LABELS, List.of(
                row(1, 0, "[]", "Result", null, null, null, null, null, null),
                row(1, 99_999_999_999L, "[99999999999999999999]", "TableScan", "DB.PUBLIC.T",
                        null, null, null, null, null),
                row(1, 2, 3_000_000_000L, "Filter", null, null, null, null, null, null)));

        var explained = SnowflakeExplainPlanMapper.map(resultSet);

        // Overflowing id / parent references degrade to "unknown" — the operators surface as
        // orphans under the root rather than failing the dry-run.
        assertThat(explained.plan().operation()).isEqualTo("Result");
        assertThat(explained.plan().children()).extracting(n -> n.operation())
                .containsExactlyInAnyOrder("TableScan", "Filter");
    }

    @Test
    void numericStringsAndMissingColumnsDegradeGracefully() throws SQLException {
        var labels = List.of("operation", "bytesAssigned");
        var resultSet = explainResult(labels, List.of(
                row("GlobalStats", "2048"),
                row("Result", "not-a-number")));

        var explained = SnowflakeExplainPlanMapper.map(resultSet);

        assertThat(explained.bytesAssigned()).isEqualTo(2048L);
        assertThat(explained.plan().operation()).isEqualTo("Result");
        assertThat(explained.plan().children()).isEmpty();
    }
}
