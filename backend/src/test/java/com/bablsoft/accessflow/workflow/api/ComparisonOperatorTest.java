package com.bablsoft.accessflow.workflow.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonOperatorTest {

    @Test
    void testEvaluatesEachOperatorBothWays() {
        assertThat(ComparisonOperator.LT.test(1, 2)).isTrue();
        assertThat(ComparisonOperator.LT.test(2, 2)).isFalse();

        assertThat(ComparisonOperator.LTE.test(2, 2)).isTrue();
        assertThat(ComparisonOperator.LTE.test(3, 2)).isFalse();

        assertThat(ComparisonOperator.GT.test(3, 2)).isTrue();
        assertThat(ComparisonOperator.GT.test(2, 2)).isFalse();

        assertThat(ComparisonOperator.GTE.test(2, 2)).isTrue();
        assertThat(ComparisonOperator.GTE.test(1, 2)).isFalse();

        assertThat(ComparisonOperator.EQ.test(2, 2)).isTrue();
        assertThat(ComparisonOperator.EQ.test(1, 2)).isFalse();
    }
}
