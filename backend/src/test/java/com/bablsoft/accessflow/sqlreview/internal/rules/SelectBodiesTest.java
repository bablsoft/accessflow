package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

class SelectBodiesTest {

    @Test
    void flattensBranchesParenthesesAndCtes() {
        var select = (Select) parse(
                "WITH c AS (SELECT 1 AS x) (SELECT a FROM t) UNION SELECT b FROM u UNION (SELECT c FROM v)");
        assertThat(SelectBodies.topLevel(select)).hasSize(4);
    }

    @Test
    void doesNotDescendIntoSubqueriesAndSkipsValues() {
        assertThat(SelectBodies.topLevel((Select) parse("SELECT a FROM (SELECT b FROM u) x"))).hasSize(1);
        assertThat(SelectBodies.topLevel((Select) parse("VALUES (1)"))).isEmpty();
        assertThat(SelectBodies.topLevel(null)).isEmpty();
    }
}
