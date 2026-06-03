package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.proxy.api.RowSecurityDirective;
import com.bablsoft.accessflow.proxy.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RowSecurityRewriterTest {

    private final MessageSource messageSource = mock(MessageSource.class);
    private final RowSecurityRewriter rewriter = new RowSecurityRewriter(messageSource);

    RowSecurityRewriterTest() {
        when(messageSource.getMessage(any(String.class), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0)); // return the key as the message
    }

    private static RowSecurityDirective dir(String table, String column, RowSecurityOperator op,
                                            Object... values) {
        return new RowSecurityDirective(UUID.randomUUID(), table, column, op, List.of(values));
    }

    private static RowSecurityDirective denyDir(String table, String column,
                                                RowSecurityOperator op) {
        return new RowSecurityDirective(UUID.randomUUID(), table, column, op, List.of());
    }

    private static long questionMarks(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }

    // ---- no-op ----------------------------------------------------------------------------------

    @Test
    void emptyDirectivesIsNoOpWithoutParsing() {
        var sql = "SELECT id FROM orders";
        var result = rewriter.rewrite(sql, List.of());
        assertThat(result.sql()).isEqualTo(sql);
        assertThat(result.binds()).isEmpty();
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void noMatchingTableIsNoOp() {
        var result = rewriter.rewrite("SELECT id FROM customers",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql()).isEqualTo("SELECT id FROM customers");
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    // ---- SELECT barrier wrap --------------------------------------------------------------------

    @Test
    void singleTableSelectIsWrappedAndBound() {
        var directive = dir("orders", "region", RowSecurityOperator.EQUALS, "EU");
        var result = rewriter.rewrite("SELECT id, region FROM orders", List.of(directive));

        assertThat(result.sql())
                .contains("(SELECT * FROM orders WHERE region = ?)")
                .contains("AS orders");
        assertThat(result.binds()).containsExactly("EU");
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
        assertThat(questionMarks(result.sql())).isEqualTo(result.binds().size());
    }

    @Test
    void schemaQualifiedPolicyMatchesUnqualifiedReference() {
        var result = rewriter.rewrite("SELECT * FROM orders",
                List.of(dir("public.orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql()).contains("(SELECT * FROM orders WHERE region = ?)");
        assertThat(result.binds()).containsExactly("EU");
    }

    @Test
    void existingAliasIsPreserved() {
        var result = rewriter.rewrite("SELECT o.id FROM orders o",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql()).contains("(SELECT * FROM orders WHERE region = ?) o");
        assertThat(result.sql()).doesNotContain("AS orders");
    }

    @Test
    void selfJoinWrapsEachOccurrenceWithItsOwnBind() {
        var result = rewriter.rewrite(
                "SELECT a.id FROM orders a JOIN orders b ON a.id = b.parent_id",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql()).contains(") a").contains(") b");
        assertThat(result.binds()).containsExactly("EU", "EU");
        assertThat(questionMarks(result.sql())).isEqualTo(2);
    }

    @Test
    void joinWithOnlyOnePoliciedTableWrapsOnlyThatTable() {
        var result = rewriter.rewrite(
                "SELECT * FROM orders o JOIN customers c ON o.cid = c.id",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql())
                .contains("(SELECT * FROM orders WHERE region = ?)")
                .contains("customers c");
        assertThat(result.binds()).containsExactly("EU");
    }

    @Test
    void multiplePoliciesOnSameTableAreAndedTogether() {
        var result = rewriter.rewrite("SELECT * FROM orders",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU"),
                        dir("orders", "tier", RowSecurityOperator.EQUALS, "gold")));
        assertThat(result.sql()).contains("region = ?").contains("tier = ?").contains("AND");
        assertThat(result.binds()).containsExactly("EU", "gold");
    }

    // ---- operators ------------------------------------------------------------------------------

    @Test
    void scalarOperatorsRenderExpectedSymbols() {
        assertThat(rewriteWhere(RowSecurityOperator.EQUALS, "EU")).contains("region = ?");
        assertThat(rewriteWhere(RowSecurityOperator.NOT_EQUALS, "EU")).contains("region").contains("?");
        assertThat(rewriteWhere(RowSecurityOperator.LESS_THAN, "5")).contains("region < ?");
        assertThat(rewriteWhere(RowSecurityOperator.LESS_THAN_OR_EQUAL, "5")).contains("region <= ?");
        assertThat(rewriteWhere(RowSecurityOperator.GREATER_THAN, "5")).contains("region > ?");
        assertThat(rewriteWhere(RowSecurityOperator.GREATER_THAN_OR_EQUAL, "5"))
                .contains("region >= ?");
    }

    private String rewriteWhere(RowSecurityOperator op, String value) {
        return rewriter.rewrite("SELECT * FROM orders",
                List.of(dir("orders", "region", op, value))).sql();
    }

    @Test
    void inOperatorExpandsToParameterListPerValue() {
        var result = rewriter.rewrite("SELECT * FROM orders",
                List.of(dir("orders", "region", RowSecurityOperator.IN, "EU", "US", "APAC")));
        assertThat(result.sql()).contains("region IN (?, ?, ?)");
        assertThat(result.binds()).containsExactly("EU", "US", "APAC");
        assertThat(questionMarks(result.sql())).isEqualTo(3);
    }

    @Test
    void notInOperatorRendersNotIn() {
        var result = rewriter.rewrite("SELECT * FROM orders",
                List.of(dir("orders", "region", RowSecurityOperator.NOT_IN, "EU")));
        assertThat(result.sql()).contains("region NOT IN (?)");
        assertThat(result.binds()).containsExactly("EU");
    }

    @Test
    void emptyValuesProduceAlwaysFalsePredicateWithNoBinds() {
        var result = rewriter.rewrite("SELECT * FROM orders",
                List.of(denyDir("orders", "region", RowSecurityOperator.EQUALS)));
        assertThat(result.sql()).contains("1 = 0");
        assertThat(result.binds()).isEmpty();
        assertThat(questionMarks(result.sql())).isZero();
    }

    @Test
    void emptyListForInOperatorAlsoDeniesWithAlwaysFalse() {
        var result = rewriter.rewrite("SELECT * FROM orders",
                List.of(denyDir("orders", "region", RowSecurityOperator.IN)));
        assertThat(result.sql()).contains("1 = 0");
        assertThat(result.binds()).isEmpty();
    }

    // ---- UPDATE / DELETE ------------------------------------------------------------------------

    @Test
    void updateAndsPredicateIntoExistingWhere() {
        var result = rewriter.rewrite("UPDATE orders SET status = 'x' WHERE id = 1",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql()).contains("WHERE id = 1 AND orders.region = ?");
        assertThat(result.binds()).containsExactly("EU");
        assertThat(questionMarks(result.sql())).isEqualTo(1);
    }

    @Test
    void updateWithoutWhereGetsNewWhere() {
        var result = rewriter.rewrite("UPDATE orders SET status = 'x'",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql()).contains("WHERE orders.region = ?");
        assertThat(result.binds()).containsExactly("EU");
    }

    @Test
    void updateUsesAliasQualifierWhenPresent() {
        var result = rewriter.rewrite("UPDATE orders o SET status = 'x'",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql()).contains("o.region = ?");
    }

    @Test
    void deleteAndsPredicateIntoWhere() {
        var result = rewriter.rewrite("DELETE FROM orders WHERE id = 1",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.sql()).contains("WHERE id = 1 AND orders.region = ?");
        assertThat(result.binds()).containsExactly("EU");
    }

    // ---- reject (HTTP 422) ----------------------------------------------------------------------

    @Test
    void unionOverPoliciedTableIsRejected() {
        assertReject("SELECT * FROM orders UNION SELECT * FROM orders",
                "error.row_security_union_unsupported",
                dir("orders", "region", RowSecurityOperator.EQUALS, "EU"));
    }

    @Test
    void cteWithPoliciedTableIsRejected() {
        assertReject("WITH x AS (SELECT 1) SELECT * FROM orders",
                "error.row_security_cte_unsupported",
                dir("orders", "region", RowSecurityOperator.EQUALS, "EU"));
    }

    @Test
    void policiedTableInsideWhereSubqueryIsRejected() {
        assertReject("SELECT * FROM customers WHERE id IN (SELECT cid FROM orders)",
                "error.row_security_subselect_unsupported",
                dir("orders", "region", RowSecurityOperator.EQUALS, "EU"));
    }

    @Test
    void policiedTableAsDerivedTableIsRejected() {
        assertReject("SELECT * FROM (SELECT * FROM orders) sub",
                "error.row_security_subselect_unsupported",
                dir("orders", "region", RowSecurityOperator.EQUALS, "EU"));
    }

    @Test
    void insertSelectFromPoliciedTableIsRejected() {
        assertReject("INSERT INTO archive SELECT * FROM orders",
                "error.row_security_insert_select_unsupported",
                dir("orders", "region", RowSecurityOperator.EQUALS, "EU"));
    }

    @Test
    void updateFromOtherPoliciedTableIsRejected() {
        assertReject("UPDATE orders o SET status = 'x' FROM customers c WHERE o.cid = c.id",
                "error.row_security_dml_join_unsupported",
                dir("orders", "region", RowSecurityOperator.EQUALS, "EU"),
                dir("customers", "region", RowSecurityOperator.EQUALS, "EU"));
    }

    @Test
    void deleteUsingOtherPoliciedTableIsRejected() {
        assertReject("DELETE FROM orders USING customers WHERE orders.cid = customers.id",
                "error.row_security_dml_join_unsupported",
                dir("orders", "region", RowSecurityOperator.EQUALS, "EU"),
                dir("customers", "region", RowSecurityOperator.EQUALS, "EU"));
    }

    @Test
    void insertWithoutSelectOnPoliciedTableIsNoOp() {
        var result = rewriter.rewrite("INSERT INTO orders (region) VALUES ('EU')",
                List.of(dir("orders", "region", RowSecurityOperator.EQUALS, "EU")));
        assertThat(result.appliedPolicyIds()).isEmpty();
        assertThat(result.binds()).isEmpty();
    }

    private void assertReject(String sql, String expectedKey, RowSecurityDirective... directives) {
        assertThatThrownBy(() -> rewriter.rewrite(sql, List.of(directives)))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessage(expectedKey);
    }
}
