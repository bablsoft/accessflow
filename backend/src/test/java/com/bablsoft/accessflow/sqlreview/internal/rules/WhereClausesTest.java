package com.bablsoft.accessflow.sqlreview.internal.rules;

import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

class WhereClausesTest {

    @Test
    void alwaysTrueCollectsTautologiesOfEveryFilterableShape() {
        assertThat(WhereClauses.alwaysTrue(parse("SELECT * FROM t WHERE 1 = 1"))).hasSize(1);
        assertThat(WhereClauses.alwaysTrue(parse("SELECT a FROM t WHERE 1 = 1 UNION SELECT a FROM u WHERE TRUE")))
                .hasSize(2);
        assertThat(WhereClauses.alwaysTrue(parse("UPDATE t SET a = 1 WHERE x = x"))).hasSize(1);
        assertThat(WhereClauses.alwaysTrue(parse("DELETE FROM t WHERE id = 1 OR 1 = 1"))).hasSize(1);
        assertThat(WhereClauses.alwaysTrue(parse("DELETE FROM t WHERE id = 1"))).isEmpty();
        assertThat(WhereClauses.alwaysTrue(parse("SELECT * FROM t"))).isEmpty();
        assertThat(WhereClauses.alwaysTrue(parse("INSERT INTO t VALUES (1)"))).isEmpty();
    }

    @Test
    void hasWhereRequiresEveryBranchToBeFiltered() {
        assertThat(WhereClauses.hasWhere(parse("SELECT * FROM t WHERE id = 1"))).isTrue();
        assertThat(WhereClauses.hasWhere(parse("SELECT * FROM t"))).isFalse();
        assertThat(WhereClauses.hasWhere(parse("SELECT a FROM t WHERE a = 1 UNION SELECT a FROM u WHERE a = 2"))).isTrue();
        assertThat(WhereClauses.hasWhere(parse("SELECT a FROM t WHERE a = 1 UNION SELECT a FROM u"))).isFalse();
        assertThat(WhereClauses.hasWhere(parse("(SELECT a FROM t WHERE a = 1)"))).isTrue();
        assertThat(WhereClauses.hasWhere(parse("UPDATE t SET a = 1 WHERE id = 1"))).isTrue();
        assertThat(WhereClauses.hasWhere(parse("UPDATE t SET a = 1"))).isFalse();
        assertThat(WhereClauses.hasWhere(parse("DELETE FROM t WHERE id = 1"))).isTrue();
        assertThat(WhereClauses.hasWhere(parse("DELETE FROM t"))).isFalse();
        assertThat(WhereClauses.hasWhere(parse("INSERT INTO t VALUES (1)"))).isFalse();
        assertThat(WhereClauses.hasWhere(parse("VALUES (1)"))).isFalse();
    }
}
