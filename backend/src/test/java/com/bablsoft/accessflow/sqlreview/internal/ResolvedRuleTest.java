package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.rules.SelectStarRule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolvedRuleTest {

    @Test
    void defaultsUseTheRulesBuiltInSeverityAndNoParams() {
        var rule = new SelectStarRule();
        var resolved = ResolvedRule.defaults(rule);
        assertThat(resolved.rule()).isSameAs(rule);
        assertThat(resolved.severity()).isEqualTo(rule.defaultSeverity());
        assertThat(resolved.params()).isEmpty();
    }

    @Test
    void copiesParamsAndRejectsNulls() {
        var params = new HashMap<String, List<String>>();
        params.put("names", List.of("a"));
        var resolved = new ResolvedRule(new SelectStarRule(), SqlReviewSeverity.OFF, params);
        params.put("later", List.of());
        assertThat(resolved.params()).containsOnlyKeys("names");
        assertThat(new ResolvedRule(new SelectStarRule(), SqlReviewSeverity.WARN, null).params()).isEmpty();
        assertThatThrownBy(() -> new ResolvedRule(null, SqlReviewSeverity.WARN, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolvedRule(new SelectStarRule(), null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
