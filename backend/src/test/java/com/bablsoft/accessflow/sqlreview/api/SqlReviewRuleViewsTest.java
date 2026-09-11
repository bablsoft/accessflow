package com.bablsoft.accessflow.sqlreview.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlReviewRuleViewsTest {

    @Test
    void paramViewDefaultsNullDefaultsToEmptyAndCopies() {
        var defaults = new ArrayList<>(List.of("pg_sleep"));
        var view = new SqlReviewRuleParamView("names", true, defaults, "[a-z]+");
        defaults.add("sleep");

        assertThat(view.defaults()).containsExactly("pg_sleep");
        assertThat(new SqlReviewRuleParamView("globs", true, null, ".*").defaults()).isEmpty();
        assertThatThrownBy(() -> new SqlReviewRuleParamView(null, true, List.of(), ".*"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SqlReviewRuleParamView("k", true, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ruleViewDefaultsNullParamsToEmptyAndRequiresIdentity() {
        var view = new SqlReviewRuleView("select_star", SqlRuleCategory.PERFORMANCE, SqlReviewSeverity.WARN,
                "SELECT *", "desc", null);

        assertThat(view.params()).isEmpty();
        assertThatThrownBy(() -> new SqlReviewRuleView(null, SqlRuleCategory.PERFORMANCE,
                SqlReviewSeverity.WARN, "n", "d", List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SqlReviewRuleView("id", null, SqlReviewSeverity.WARN, "n", "d", List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SqlReviewRuleView("id", SqlRuleCategory.PERFORMANCE, null, "n", "d",
                List.of())).isInstanceOf(NullPointerException.class);
    }
}
