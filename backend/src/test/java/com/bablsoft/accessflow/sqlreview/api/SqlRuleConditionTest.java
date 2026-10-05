package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlRuleConditionTest {

    @Test
    void listOperandsDefaultToEmptyAndAreDefensivelyCopied() {
        assertThat(new SqlRuleCondition.And(null).children()).isEmpty();
        assertThat(new SqlRuleCondition.Or(null).children()).isEmpty();
        assertThat(new SqlRuleCondition.QueryTypeIn(null).anyOf()).isEmpty();
        assertThat(new SqlRuleCondition.ReferencedTableMatches(null).globs()).isEmpty();
        assertThat(new SqlRuleCondition.ReferencedColumnMatches(null).globs()).isEmpty();
        assertThat(new SqlRuleCondition.FunctionCalled(null).names()).isEmpty();

        var names = new ArrayList<>(List.of("dblink"));
        var leaf = new SqlRuleCondition.FunctionCalled(names);
        names.add("sleep");
        assertThat(leaf.names()).containsExactly("dblink");
        assertThat(new SqlRuleCondition.QueryTypeIn(Set.of(QueryType.DDL)).anyOf()).containsExactly(QueryType.DDL);
    }

    @Test
    void notRequiresAChild() {
        assertThatThrownBy(() -> new SqlRuleCondition.Not(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SqlRuleCondition.Not(new SqlRuleCondition.HasWhereClause(true)).child())
                .isEqualTo(new SqlRuleCondition.HasWhereClause(true));
    }
}
