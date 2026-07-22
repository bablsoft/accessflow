package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabricksRowSecurityApplierTest {

    private final DatabricksQueryParser parser = new DatabricksQueryParser(TestMessages.keyEcho());
    private final DatabricksRowSecurityApplier applier =
            new DatabricksRowSecurityApplier(TestMessages.keyEcho());

    private static final UUID POLICY = UUID.randomUUID();

    // ---- splicing -------------------------------------------------------------------------------

    @Test
    void noMatchingDirectiveLeavesStatementUntouched() {
        var applied = applier.apply(parse("SELECT * FROM orders"),
                List.of(directive("other_table", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(applied.statement()).isEqualTo("SELECT * FROM orders");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).isEmpty();
        assertThat(applied.denyAll()).isFalse();
    }

    @Test
    void insertsWhereWhenAbsent() {
        var applied = applier.apply(parse("SELECT * FROM orders"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM orders WHERE (`tenant` = :afp_1)");
        assertThat(applied.parameters()).containsExactly(java.util.Map.entry("afp_1", "acme"));
        assertThat(applied.appliedPolicyIds()).containsExactly(POLICY);
    }

    @Test
    void wrapsExistingWhereAndAnds() {
        var applied = applier.apply(parse("SELECT * FROM orders WHERE total > 5"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM orders WHERE (total > 5) AND (`tenant` = :afp_1)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GROUP BY tenant", "HAVING count(*) > 1", "QUALIFY rn = 1",
            "ORDER BY id", "SORT BY id", "CLUSTER BY id", "DISTRIBUTE BY id", "LIMIT 5",
            "OFFSET 5"})
    void insertsWhereBeforeTailClauses(String tail) {
        var applied = applier.apply(parse("SELECT * FROM orders " + tail),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM orders WHERE (`tenant` = :afp_1) " + tail);
    }

    @Test
    void spliceWorksForUpdateAndDelete() {
        var update = applier.apply(parse("UPDATE orders SET total = 0 WHERE id = 9"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(update.statement())
                .isEqualTo("UPDATE orders SET total = 0 WHERE (id = 9) AND (`tenant` = :afp_1)");
        var delete = applier.apply(parse("DELETE FROM orders"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(delete.statement())
                .isEqualTo("DELETE FROM orders WHERE (`tenant` = :afp_1)");
    }

    @Test
    void trailingSemicolonSurvivesTheSplice() {
        var applied = applier.apply(parse("SELECT * FROM orders;"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM orders WHERE (`tenant` = :afp_1) ;");
    }

    // ---- operators ------------------------------------------------------------------------------

    @Test
    void multipleDirectivesAndScalarOperators() {
        var applied = applier.apply(parse("SELECT * FROM orders"), List.of(
                directive("orders", "tenant", RowSecurityOperator.NOT_EQUALS, List.of("evil")),
                directive("orders", "total", RowSecurityOperator.LESS_THAN, List.of(100)),
                directive("orders", "score", RowSecurityOperator.LESS_THAN_OR_EQUAL, List.of(5)),
                directive("orders", "age", RowSecurityOperator.GREATER_THAN, List.of(1)),
                directive("orders", "rank", RowSecurityOperator.GREATER_THAN_OR_EQUAL,
                        List.of(2))));
        assertThat(applied.statement()).isEqualTo("SELECT * FROM orders WHERE"
                + " (`tenant` <> :afp_1 AND `total` < :afp_2 AND `score` <= :afp_3"
                + " AND `age` > :afp_4 AND `rank` >= :afp_5)");
        assertThat(applied.parameters()).containsExactly(
                java.util.Map.entry("afp_1", "evil"),
                java.util.Map.entry("afp_2", 100),
                java.util.Map.entry("afp_3", 5),
                java.util.Map.entry("afp_4", 1),
                java.util.Map.entry("afp_5", 2));
    }

    @Test
    void inAndNotInBindEachValue() {
        var in = applier.apply(parse("SELECT * FROM orders"),
                List.of(directive("orders", "region", RowSecurityOperator.IN,
                        List.of("eu", "us"))));
        assertThat(in.statement())
                .isEqualTo("SELECT * FROM orders WHERE (`region` IN (:afp_1, :afp_2))");
        var notIn = applier.apply(parse("SELECT * FROM orders"),
                List.of(directive("orders", "region", RowSecurityOperator.NOT_IN,
                        List.of("eu", "us"))));
        assertThat(notIn.statement())
                .isEqualTo("SELECT * FROM orders WHERE (`region` NOT IN (:afp_1, :afp_2))");
        assertThat(notIn.parameters()).containsExactly(
                java.util.Map.entry("afp_1", "eu"), java.util.Map.entry("afp_2", "us"));
    }

    @Test
    void isNullIsUnaryAndBindsNothing() {
        var applied = applier.apply(parse("SELECT * FROM orders"),
                List.of(new RowSecurityDirective(POLICY, "orders", "deleted_at",
                        RowSecurityOperator.IS_NULL, List.of())));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM orders WHERE (`deleted_at` IS NULL)");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.denyAll()).isFalse();
    }

    @Test
    void backticksInColumnNamesAreStripped() {
        var applied = applier.apply(parse("SELECT * FROM orders"),
                List.of(directive("orders", "ten`ant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(applied.statement()).contains("`tenant` = :afp_1");
    }

    // ---- deny-all -------------------------------------------------------------------------------

    @Test
    void emptyValuesOnMultiValueOperatorIsDenyAll() {
        var applied = applier.apply(parse("SELECT * FROM orders"),
                List.of(directive("orders", "region", RowSecurityOperator.IN, List.of())));
        assertThat(applied.denyAll()).isTrue();
        assertThat(applied.statement()).isEqualTo("SELECT * FROM orders");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).containsExactly(POLICY);
    }

    @Test
    void emptyValuesOnScalarOperatorAlsoFailsClosedAsDenyAll() {
        var applied = applier.apply(parse("SELECT * FROM orders"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, List.of())));
        assertThat(applied.denyAll()).isTrue();
    }

    // ---- rejections -----------------------------------------------------------------------------

    @Test
    void insertIntoPoliciedTableIsRejected() {
        assertThatThrownBy(() -> applier.apply(parse("INSERT INTO orders VALUES (1)"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme")))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_databricks_insert_unsupported")
                .hasMessageContaining("orders");
    }

    @Test
    void mergeOnPoliciedTableFailsClosed() {
        assertThatThrownBy(() -> applier.apply(
                parse("MERGE INTO orders t USING updates s ON t.id = s.id"
                        + " WHEN MATCHED THEN UPDATE SET *"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme")))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_databricks_unrewritable");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "WITH r AS (SELECT * FROM orders) SELECT * FROM r",
            "SELECT * FROM orders WHERE id IN (SELECT id FROM orders)",
            "SELECT (SELECT max(id) FROM orders) FROM orders",
            "SELECT * FROM orders o JOIN orders p ON o.id = p.id",
            "SELECT * FROM orders a, orders b",
            "SELECT * FROM orders UNION SELECT * FROM orders",
            "SELECT * FROM orders INTERSECT SELECT * FROM orders",
            "SELECT * FROM orders EXCEPT SELECT * FROM orders",
            "SELECT * FROM orders MINUS SELECT * FROM orders",
            "SELECT * FROM orders LATERAL VIEW explode(items) i AS item"})
    void complexShapesOnPoliciedTablesFailClosed(String sql) {
        assertThatThrownBy(() -> applier.apply(parse(sql),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme")))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_databricks_unrewritable")
                .hasMessageContaining("orders");
    }

    @Test
    void multipleDistinctTablesFailClosed() {
        assertThatThrownBy(() -> applier.apply(
                parse("INSERT INTO archive SELECT * FROM orders"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme")))))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void literalAfpMarkerTextInUserSqlFailsClosed() {
        assertThatThrownBy(() -> applier.apply(
                parse("SELECT * FROM orders WHERE note = ':afp_1'"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme")))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_databricks_unrewritable");
    }

    @Test
    void directiveMatchesByLastSegmentCaseInsensitively() {
        var applied = applier.apply(parse("SELECT * FROM main.sales.Orders"),
                List.of(directive("warehouse.ORDERS", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        assertThat(applied.statement()).contains("WHERE (`tenant` = :afp_1)");
    }

    @Test
    void ddlIsUnaffectedByDirectives() {
        var applied = applier.apply(parse("TRUNCATE TABLE orders"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS,
                        List.of("acme"))));
        // TRUNCATE has no WHERE concept; the applier only guards rewritable DML/SELECT shapes.
        assertThat(applied.denyAll()).isFalse();
    }

    private DatabricksStatement parse(String sql) {
        return parser.parseStatement(sql);
    }

    private static RowSecurityDirective directive(String table, String column,
                                                  RowSecurityOperator operator,
                                                  List<Object> values) {
        return new RowSecurityDirective(POLICY, table, column, operator, values);
    }
}
