package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupportExpressions.expression;
import static org.assertj.core.api.Assertions.assertThat;

class TautologiesTest {

    @Test
    void recognisesLiteralAndSelfComparisons() {
        assertThat(Tautologies.isTautology(expression("1 = 1"))).isTrue();
        assertThat(Tautologies.isTautology(expression("1.5 = 1.5"))).isTrue();
        assertThat(Tautologies.isTautology(expression("'a' = 'a'"))).isTrue();
        assertThat(Tautologies.isTautology(expression("TRUE"))).isTrue();
        assertThat(Tautologies.isTautology(expression("TRUE = TRUE"))).isTrue();
        assertThat(Tautologies.isTautology(expression("x = x"))).isTrue();
        assertThat(Tautologies.isTautology(expression("(x) = (x)"))).isTrue();
        assertThat(Tautologies.isTautology(expression("((1 = 1))"))).isTrue();
    }

    @Test
    void rejectsRealPredicates() {
        assertThat(Tautologies.isTautology(expression("1 = 2"))).isFalse();
        assertThat(Tautologies.isTautology(expression("1 = '1'"))).isFalse();
        assertThat(Tautologies.isTautology(expression("FALSE"))).isFalse();
        assertThat(Tautologies.isTautology(expression("x = y"))).isFalse();
        assertThat(Tautologies.isTautology(expression("x <> x"))).isFalse();
        assertThat(Tautologies.isTautology(expression("1"))).isFalse();
        assertThat(Tautologies.isTautology(expression("f(x) = f(x)"))).isFalse();
        assertThat(Tautologies.isTautology(null)).isFalse();
        var oneSided = new EqualsTo();
        oneSided.setLeftExpression(expression("1"));
        assertThat(Tautologies.isTautology(oneSided)).isFalse();
    }

    @Test
    void alwaysTrueWalksTheTopLevelOrChainOnly() {
        assertThat(Tautologies.isAlwaysTrue(expression("id = 1 OR 1 = 1"))).isTrue();
        assertThat(Tautologies.isAlwaysTrue(expression("1 = 1 OR id = 1"))).isTrue();
        assertThat(Tautologies.isAlwaysTrue(expression("a = 1 OR b = 2 OR TRUE"))).isTrue();
        assertThat(Tautologies.isAlwaysTrue(expression("(id = 1 OR 1 = 1)"))).isTrue();
        assertThat(Tautologies.isAlwaysTrue(expression("id = 1 AND 1 = 1"))).isFalse();
        assertThat(Tautologies.isAlwaysTrue(expression("(id = 1 OR 1 = 1) AND b = 2"))).isFalse();
        assertThat(Tautologies.isAlwaysTrue(expression("id = 1 OR b = 2"))).isFalse();
        assertThat(Tautologies.isAlwaysTrue(null)).isFalse();
    }

    @Test
    void unwrapStopsAtMultiElementListsAndNonParentheses() {
        assertThat(Tautologies.unwrap(expression("(1, 2)")).toString()).isEqualTo("(1, 2)");
        assertThat(Tautologies.unwrap(expression("x")).toString()).isEqualTo("x");
        assertThat(Tautologies.unwrap(null)).isNull();
    }
}
