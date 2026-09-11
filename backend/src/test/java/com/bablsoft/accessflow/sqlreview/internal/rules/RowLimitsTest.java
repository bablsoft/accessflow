package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

class RowLimitsTest {

    private static Select select(String sql) {
        return (Select) parse(sql);
    }

    @Test
    void recognisesEveryLimitDialect() {
        assertThat(RowLimits.hasRowLimit(select("SELECT a FROM t LIMIT 1"))).isTrue();
        assertThat(RowLimits.hasRowLimit(select("SELECT TOP 1 a FROM t"))).isTrue();
        assertThat(RowLimits.hasRowLimit(select("SELECT a FROM t FETCH FIRST 1 ROWS ONLY"))).isTrue();
        assertThat(RowLimits.hasRowLimit(select("SELECT a FROM t UNION SELECT b FROM u LIMIT 1"))).isTrue();
        assertThat(RowLimits.hasRowLimit(select("(SELECT a FROM t) UNION (SELECT b FROM u) LIMIT 1"))).isTrue();
        assertThat(RowLimits.hasRowLimit(select("(SELECT a FROM t LIMIT 1)"))).isTrue();
    }

    @Test
    void rejectsNonLimitsAndNull() {
        assertThat(RowLimits.hasRowLimit(select("SELECT a FROM t"))).isFalse();
        assertThat(RowLimits.hasRowLimit(select("SELECT a FROM t LIMIT ALL"))).isFalse();
        assertThat(RowLimits.hasRowLimit(select("SELECT a FROM t OFFSET 5"))).isFalse();
        assertThat(RowLimits.hasRowLimit(select("SELECT a FROM t UNION SELECT b FROM u"))).isFalse();
        assertThat(RowLimits.hasRowLimit(select("VALUES (1)"))).isFalse();
        assertThat(RowLimits.hasRowLimit(null)).isFalse();
        var empty = new SetOperationList();
        assertThat(RowLimits.hasRowLimit(empty)).isFalse();
        assertThat(RowLimits.hasOrderBy(empty)).isFalse();
    }

    @Test
    void detectsOrderByAcrossShapes() {
        assertThat(RowLimits.hasOrderBy(select("SELECT a FROM t ORDER BY a"))).isTrue();
        assertThat(RowLimits.hasOrderBy(select("SELECT a FROM t UNION SELECT b FROM u ORDER BY 1"))).isTrue();
        assertThat(RowLimits.hasOrderBy(select("(SELECT a FROM t ORDER BY a)"))).isTrue();
        assertThat(RowLimits.hasOrderBy(select("SELECT a FROM t"))).isFalse();
        assertThat(RowLimits.hasOrderBy(select("VALUES (1)"))).isFalse();
        assertThat(RowLimits.hasOrderBy(null)).isFalse();
    }
}
