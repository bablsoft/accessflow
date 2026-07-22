package com.bablsoft.accessflow.engine.snowflake;

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

class SnowflakeRowSecurityApplierTest {

    private final SnowflakeQueryParser parser = new SnowflakeQueryParser(TestMessages.keyEcho());
    private final SnowflakeRowSecurityApplier applier =
            new SnowflakeRowSecurityApplier(TestMessages.keyEcho());

    private static RowSecurityDirective directive(String table, String column,
                                                  RowSecurityOperator op, Object... values) {
        return new RowSecurityDirective(UUID.randomUUID(), table, column, op, List.of(values));
    }

    // ---- splice shapes -------------------------------------------------------------------------

    @Test
    void leavesStatementUnchangedWhenNoDirectiveMatches() {
        var statement = parser.parseStatement("SELECT * FROM orders");
        var applied = applier.apply(statement,
                List.of(directive("other", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement()).isEqualTo("SELECT * FROM orders");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).isEmpty();
        assertThat(applied.denyAll()).isFalse();
    }

    @Test
    void addsWhereWhenAbsentAndBindsPositionalParameter() {
        var statement = parser.parseStatement("SELECT * FROM orders");
        var applied = applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement()).isEqualTo("SELECT * FROM orders WHERE (\"tenant\" = ?)");
        assertThat(applied.parameters()).containsExactly("acme");
        assertThat(applied.appliedPolicyIds()).hasSize(1);
    }

    @Test
    void andsIntoExistingWhereClause() {
        var statement = parser.parseStatement("SELECT * FROM orders WHERE status = 'OPEN'");
        var applied = applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM orders WHERE (status = 'OPEN') AND (\"tenant\" = ?)");
        assertThat(applied.parameters()).containsExactly("acme");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GROUP BY region",
            "ORDER BY id",
            "LIMIT 10",
            "QUALIFY ROW_NUMBER() OVER (PARTITION BY id ORDER BY ts) = 1",
    })
    void insertsWhereBeforeTailClauses(String tail) {
        var statement = parser.parseStatement("SELECT region FROM orders " + tail);
        var applied = applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement())
                .isEqualTo("SELECT region FROM orders WHERE (\"tenant\" = ?) " + tail);
    }

    @Test
    void splicesExistingWhereAheadOfOrderByTail() {
        var statement = parser.parseStatement(
                "SELECT * FROM orders WHERE status = 'OPEN' ORDER BY id");
        var applied = applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement()).isEqualTo(
                "SELECT * FROM orders WHERE (status = 'OPEN') AND (\"tenant\" = ?) ORDER BY id");
    }

    @Test
    void splicesUpdateAndDelete() {
        var update = applier.apply(
                parser.parseStatement("UPDATE orders SET status = 'X' WHERE id = 1"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(update.statement())
                .isEqualTo("UPDATE orders SET status = 'X' WHERE (id = 1) AND (\"tenant\" = ?)");

        var delete = applier.apply(parser.parseStatement("DELETE FROM orders"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(delete.statement()).isEqualTo("DELETE FROM orders WHERE (\"tenant\" = ?)");
    }

    @Test
    void combinesMultipleDirectivesAsConjunction() {
        var applied = applier.apply(parser.parseStatement("SELECT * FROM orders"),
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"),
                        directive("orders", "amount", RowSecurityOperator.LESS_THAN, 100)));
        assertThat(applied.statement()).isEqualTo(
                "SELECT * FROM orders WHERE (\"tenant\" = ? AND \"amount\" < ?)");
        assertThat(applied.parameters()).containsExactly("acme", 100);
        assertThat(applied.appliedPolicyIds()).hasSize(2);
    }

    // ---- operators -------------------------------------------------------------------------------

    @Test
    void rendersComparisonOperators() {
        var statement = parser.parseStatement("SELECT * FROM orders");
        assertThat(applier.apply(statement, List.of(
                directive("orders", "a", RowSecurityOperator.NOT_EQUALS, 1))).statement())
                .contains("\"a\" <> ?");
        assertThat(applier.apply(statement, List.of(
                directive("orders", "a", RowSecurityOperator.LESS_THAN_OR_EQUAL, 1))).statement())
                .contains("\"a\" <= ?");
        assertThat(applier.apply(statement, List.of(
                directive("orders", "a", RowSecurityOperator.GREATER_THAN, 1))).statement())
                .contains("\"a\" > ?");
        assertThat(applier.apply(statement, List.of(
                directive("orders", "a", RowSecurityOperator.GREATER_THAN_OR_EQUAL, 1))).statement())
                .contains("\"a\" >= ?");
    }

    @Test
    void expandsInAndNotInLists() {
        var in = applier.apply(parser.parseStatement("SELECT * FROM orders"),
                List.of(directive("orders", "region", RowSecurityOperator.IN, "eu", "us")));
        assertThat(in.statement())
                .isEqualTo("SELECT * FROM orders WHERE (\"region\" IN (?, ?))");
        assertThat(in.parameters()).containsExactly("eu", "us");

        var notIn = applier.apply(parser.parseStatement("SELECT * FROM orders"),
                List.of(directive("orders", "region", RowSecurityOperator.NOT_IN, "apac")));
        assertThat(notIn.statement())
                .isEqualTo("SELECT * FROM orders WHERE (\"region\" NOT IN (?))");
        assertThat(notIn.parameters()).containsExactly("apac");
    }

    @Test
    void isNullOperatorSplicesWithoutParameter() {
        var applied = applier.apply(parser.parseStatement("SELECT * FROM orders"),
                List.of(directive("orders", "deleted_at", RowSecurityOperator.IS_NULL)));
        assertThat(applied.statement())
                .isEqualTo("SELECT * FROM orders WHERE (\"deleted_at\" IS NULL)");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.denyAll()).isFalse();
        assertThat(applied.appliedPolicyIds()).hasSize(1);
    }

    @Test
    void escapesColumnIdentifiers() {
        var applied = applier.apply(parser.parseStatement("SELECT * FROM orders"),
                List.of(directive("orders", "we\"ird", RowSecurityOperator.EQUALS, 1)));
        assertThat(applied.statement()).contains("\"we\"\"ird\" = ?");
    }

    // ---- deny-all --------------------------------------------------------------------------------

    @Test
    void emptyValuesAreFailClosedDenyAll() {
        var applied = applier.apply(parser.parseStatement("SELECT * FROM orders"),
                List.of(new RowSecurityDirective(UUID.randomUUID(), "orders", "tenant",
                        RowSecurityOperator.EQUALS, List.of())));
        assertThat(applied.denyAll()).isTrue();
        assertThat(applied.statement()).isEqualTo("SELECT * FROM orders");
        assertThat(applied.parameters()).isEmpty();
        assertThat(applied.appliedPolicyIds()).hasSize(1);
    }

    // ---- INSERT / MERGE / DDL --------------------------------------------------------------------

    @Test
    void rejectsInsertIntoPolicedTable() {
        var statement = parser.parseStatement("INSERT INTO orders VALUES (1)");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_snowflake_insert_unsupported");
    }

    @Test
    void rejectsInsertSelectReadingPolicedTable() {
        var statement = parser.parseStatement("INSERT INTO log_copy SELECT * FROM orders");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_snowflake_unrewritable");
    }

    @Test
    void rejectsMergeTouchingPolicedTable() {
        var statement = parser.parseStatement(
                "MERGE INTO orders USING stage ON orders.id = stage.id "
                        + "WHEN MATCHED THEN UPDATE SET a = 1");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_snowflake_unrewritable");
    }

    @Test
    void ddlOnPolicedTableIsUnaffected() {
        var statement = parser.parseStatement("TRUNCATE TABLE orders");
        var applied = applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement()).isEqualTo("TRUNCATE TABLE orders");
        assertThat(applied.denyAll()).isFalse();
    }

    // ---- structural fail-closed ------------------------------------------------------------------

    @Test
    void rejectsCteOverPolicedTable() {
        var statement = parser.parseStatement(
                "WITH x AS (SELECT * FROM orders) SELECT * FROM x");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_snowflake_unrewritable");
    }

    @Test
    void rejectsSubqueryShapes() {
        var inWhere = parser.parseStatement(
                "SELECT * FROM orders WHERE id IN (SELECT id FROM orders)");
        assertThatThrownBy(() -> applier.apply(inWhere,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class);

        var qualifySubquery = parser.parseStatement(
                "SELECT * FROM orders QUALIFY id = (SELECT MAX(id) FROM orders)");
        assertThatThrownBy(() -> applier.apply(qualifySubquery,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class);

        var parenthesized = parser.parseStatement("(SELECT * FROM orders)");
        assertThatThrownBy(() -> applier.apply(parenthesized,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void rejectsJoinAndCommaJoinShapes() {
        var join = parser.parseStatement(
                "SELECT * FROM orders JOIN orders o2 ON orders.id = o2.id");
        assertThatThrownBy(() -> applier.apply(join,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class);

        var commaJoin = parser.parseStatement("SELECT * FROM orders o1, orders o2 WHERE 1 = 1");
        assertThatThrownBy(() -> applier.apply(commaJoin,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }

    @Test
    void rejectsSetOperations() {
        for (var op : List.of("UNION", "UNION ALL", "INTERSECT", "EXCEPT", "MINUS")) {
            var statement = parser.parseStatement(
                    "SELECT id FROM orders " + op + " SELECT id FROM orders");
            assertThatThrownBy(() -> applier.apply(statement,
                    List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                    .as(op)
                    .isInstanceOf(UnrewritableRowSecurityException.class);
        }
    }

    @Test
    void rejectsMultiTableStatements() {
        var statement = parser.parseStatement("DELETE FROM orders USING refs WHERE orders.id = refs.id");
        assertThatThrownBy(() -> applier.apply(statement,
                List.of(directive("orders", "tenant", RowSecurityOperator.EQUALS, "acme"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("error.row_security_snowflake_unrewritable");
    }

    // ---- matching --------------------------------------------------------------------------------

    @Test
    void matchesByLastSegmentCaseInsensitively() {
        var applied = applier.apply(parser.parseStatement("SELECT * FROM analytics.public.Orders"),
                List.of(directive("ORDERS", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(applied.statement()).contains("WHERE (\"tenant\" = ?)");

        var qualifiedDirective = applier.apply(parser.parseStatement("SELECT * FROM orders"),
                List.of(directive("public.orders", "tenant", RowSecurityOperator.EQUALS, "acme")));
        assertThat(qualifiedDirective.statement()).contains("WHERE (\"tenant\" = ?)");
    }

    @Test
    void nullDirectivesLeaveStatementUnchanged() {
        var statement = parser.parseStatement("SELECT * FROM orders");
        var applied = applier.apply(statement, null);
        assertThat(applied.statement()).isEqualTo("SELECT * FROM orders");
        assertThat(applied.denyAll()).isFalse();
    }
}
