package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

class CartesianJoinsTest {

    private static int count(String sql) {
        return CartesianJoins.find(StatementWalker.walk(parse(sql))).size();
    }

    @Test
    void findsEveryCartesianShapeAndNothingElse() {
        assertThat(count("SELECT * FROM a CROSS JOIN b")).isEqualTo(1);
        assertThat(count("SELECT * FROM a JOIN b")).isEqualTo(1);
        assertThat(count("SELECT * FROM a, b")).isEqualTo(1);
        assertThat(count("SELECT * FROM a, b WHERE a.id = b.a_id")).isZero();
        assertThat(count("SELECT * FROM a JOIN b ON a.id = b.a_id")).isZero();
        assertThat(count("SELECT * FROM a JOIN b USING (id)")).isZero();
        assertThat(count("SELECT * FROM a NATURAL JOIN b")).isZero();
        assertThat(count("SELECT * FROM a")).isZero();
        assertThat(count("SELECT * FROM t WHERE x IN (SELECT 1 FROM a CROSS JOIN b)")).isEqualTo(1);
    }

    @Test
    void describesTablesAliasesAndOtherItems() {
        assertThat(CartesianJoins.describe(new Table("Sales", "Orders"))).isEqualTo("sales.orders");
        var sub = new ParenthesedSelect().withAlias(new net.sf.jsqlparser.expression.Alias("Sub"));
        assertThat(CartesianJoins.describe(sub)).isEqualTo("sub");
        assertThat(CartesianJoins.describe(null)).isEmpty();
    }

    @Test
    void columnPairsHandleNullNonComparisonsAndNesting() {
        assertThat(CartesianJoins.columnPairs(null)).isEmpty();
        assertThat(CartesianJoins.columnPairs(RuleTestSupportExpressions.expression("t.id = 5"))).isEmpty();
        assertThat(CartesianJoins.columnPairs(RuleTestSupportExpressions.expression("NOT t.id = u.id"))).isEmpty();
        assertThat(CartesianJoins.columnPairs(RuleTestSupportExpressions.expression("(t.id = u.id OR a = b) AND t.x = t.y")))
                .containsExactly(new CartesianJoins.ColumnPair("t", "u"), new CartesianJoins.ColumnPair(null, null),
                        new CartesianJoins.ColumnPair("t", "t"));
        assertThat(CartesianJoins.columnPairs(RuleTestSupportExpressions.expression("year = u.year AND t.x = day")))
                .containsExactly(new CartesianJoins.ColumnPair(null, "u"), new CartesianJoins.ColumnPair("t", null));
    }
}
