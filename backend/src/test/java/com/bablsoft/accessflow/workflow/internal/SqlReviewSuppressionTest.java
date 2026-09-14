package com.bablsoft.accessflow.workflow.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlReviewSuppressionTest {

    @Test
    void copiesItsListsDefensively() {
        var rules = new ArrayList<>(List.of("select_star"));
        var paths = new ArrayList<>(List.of(SqlReviewSuppression.SuppressedAutoApproval.REVIEW_PLAN));

        var suppression = new SqlReviewSuppression(rules, paths);
        rules.add("cross_join");
        paths.add(SqlReviewSuppression.SuppressedAutoApproval.GRANT_FAST_PATH);

        assertThat(suppression.blockingRuleIds()).containsExactly("select_star");
        assertThat(suppression.paths())
                .containsExactly(SqlReviewSuppression.SuppressedAutoApproval.REVIEW_PLAN);
    }

    @Test
    void rejectsASuppressionThatNamesNoPath() {
        assertThatThrownBy(() -> new SqlReviewSuppression(List.of("select_star"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDecisionConvenienceConstructorCarriesNoSuppression() {
        var decision = new QueryDecision(QueryDecisionKind.PLAN_APPROVED,
                com.bablsoft.accessflow.core.api.QueryStatus.APPROVED, null, null, null, null, null,
                null);

        assertThat(decision.sqlReviewSuppression()).isNull();
    }
}
