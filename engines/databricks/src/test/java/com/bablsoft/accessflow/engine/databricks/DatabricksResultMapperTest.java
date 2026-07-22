package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.engine.databricks.DatabricksStatementClient.Column;
import com.bablsoft.accessflow.engine.databricks.DatabricksStatementClient.StatementResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatabricksResultMapperTest {

    private final DatabricksResultMapper mapper = new DatabricksResultMapper();
    private static final Duration DURATION = Duration.ofMillis(42);

    @Test
    void mapsColumnsWithBestEffortSqlTypes() {
        var result = mapper.materialize(result(
                        List.of(new Column("id", "BIGINT"), new Column("name", "STRING"),
                                new Column("active", "BOOLEAN"), new Column("price", "DECIMAL(10,2)"),
                                new Column("tags", "ARRAY<STRING>"), new Column("ts", "TIMESTAMP")),
                        List.of(row("1", "Ada", "true", "9.99", "[\"a\"]", "2026-01-01T00:00:00Z")),
                        false),
                100, DURATION, List.of(), List.of());
        assertThat(result.columns()).extracting("name")
                .containsExactly("id", "name", "active", "price", "tags", "ts");
        assertThat(result.columns()).extracting("jdbcType").containsExactly(
                Types.BIGINT, Types.VARCHAR, Types.BOOLEAN, Types.DECIMAL, Types.OTHER,
                Types.TIMESTAMP);
        assertThat(result.columns()).extracting("typeName")
                .contains("BIGINT", "DECIMAL(10,2)", "ARRAY<STRING>");
    }

    @Test
    void convertsValuesByTypeNameBestEffort() {
        var result = mapper.materialize(result(
                        List.of(new Column("i", "INT"), new Column("l", "BIGINT"),
                                new Column("b", "BOOLEAN"), new Column("d", "DOUBLE"),
                                new Column("dec", "DECIMAL(4,1)"), new Column("s", "STRING"),
                                new Column("bad", "INT")),
                        List.of(row("7", "9007199254740993", "true", "1.5", "12.5", "txt",
                                "not-a-number")),
                        false),
                100, DURATION, List.of(), List.of());
        assertThat(result.rows().get(0)).containsExactly(
                7L, 9007199254740993L, Boolean.TRUE, new BigDecimal("1.5"),
                new BigDecimal("12.5"), "txt", "not-a-number");
    }

    @Test
    void nullsStayNullAndNonBooleanStringsStayRaw() {
        var result = mapper.materialize(result(
                        List.of(new Column("b", "BOOLEAN"), new Column("i", "INT")),
                        List.of(row("maybe", null)), false),
                100, DURATION, List.of(), List.of());
        assertThat(result.rows().get(0)).containsExactly("maybe", null);
    }

    @Test
    void dropsTheSentinelRowAndFlagsTruncationAtTheRowCap() {
        var result = mapper.materialize(result(
                        List.of(new Column("id", "INT")),
                        List.of(row("1"), row("2"), row("3")), false),
                2, DURATION, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void manifestTruncationFlagPropagatesEvenUnderTheCap() {
        var result = mapper.materialize(result(
                        List.of(new Column("id", "INT")), List.of(row("1")), true),
                100, DURATION, List.of(), List.of());
        assertThat(result.rows()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void noTruncationWhenUnderCapAndManifestClean() {
        var result = mapper.materialize(result(
                        List.of(new Column("id", "INT")), List.of(row("1")), false),
                100, DURATION, List.of(), List.of());
        assertThat(result.truncated()).isFalse();
        assertThat(result.duration()).isEqualTo(DURATION);
    }

    @Test
    void restrictedColumnWithoutDirectiveIsFullyMasked() {
        var result = mapper.materialize(result(
                        List.of(new Column("id", "INT"), new Column("ssn", "STRING")),
                        List.of(row("1", "111-22-3333")), false),
                100, DURATION, List.of("SSN"), List.of());
        assertThat(result.rows().get(0)).containsExactly(1L, "***");
        assertThat(result.columns().get(1).restricted()).isTrue();
        assertThat(result.columns().get(0).restricted()).isFalse();
        assertThat(result.appliedMaskingPolicyIds()).isEmpty();
    }

    @Test
    void directiveMasksApplyThroughColumnMaskerAndWinOverRestricted() {
        var emailPolicy = UUID.randomUUID();
        var partialPolicy = UUID.randomUUID();
        var result = mapper.materialize(result(
                        List.of(new Column("email", "STRING"), new Column("card", "STRING")),
                        List.of(row("ada@example.com", "4111111111111111")), false),
                100, DURATION, List.of("email"), List.of(
                        new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(),
                                emailPolicy),
                        new ColumnMaskDirective("orders.card", MaskingStrategy.PARTIAL,
                                Map.of("visible_suffix", "4"), partialPolicy)));
        assertThat(result.rows().get(0).get(0)).isEqualTo("a***@example.com");
        assertThat(result.rows().get(0).get(1)).isEqualTo("************1111");
        assertThat(result.appliedMaskingPolicyIds())
                .containsExactlyInAnyOrder(emailPolicy, partialPolicy);
        assertThat(result.columns()).allMatch(c -> c.restricted());
    }

    @Test
    void maskedNullStaysNull() {
        var result = mapper.materialize(result(
                        List.of(new Column("email", "STRING")),
                        List.of(row((String) null)), false),
                100, DURATION, List.of("email"), List.of());
        assertThat(result.rows().get(0)).containsExactly((Object) null);
    }

    @Test
    void extractsAffectedRowsFromDmlResultShape() {
        assertThat(mapper.affectedRows(result(
                List.of(new Column("num_affected_rows", "BIGINT")), List.of(row("5")), false)))
                .isEqualTo(5);
        assertThat(mapper.affectedRows(result(
                List.of(new Column("NUM_AFFECTED_ROWS", "BIGINT"),
                        new Column("num_inserted_rows", "BIGINT")),
                List.of(row("3", "2")), false))).isEqualTo(3);
    }

    @Test
    void affectedRowsIsZeroWhenShapeAbsentOrUnparseable() {
        assertThat(mapper.affectedRows(result(
                List.of(new Column("id", "INT")), List.of(row("1")), false))).isZero();
        assertThat(mapper.affectedRows(result(
                List.of(new Column("num_affected_rows", "BIGINT")), List.of(), false))).isZero();
        assertThat(mapper.affectedRows(result(
                List.of(new Column("num_affected_rows", "BIGINT")), List.of(row("x")), false)))
                .isZero();
    }

    private static StatementResult result(List<Column> columns, List<List<String>> rows,
                                          boolean truncated) {
        return new StatementResult(columns, rows, truncated);
    }

    private static List<String> row(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
