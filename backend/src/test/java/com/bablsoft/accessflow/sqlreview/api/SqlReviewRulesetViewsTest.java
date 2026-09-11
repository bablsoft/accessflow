package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers the compact constructors of the ruleset view and command records. */
class SqlReviewRulesetViewsTest {

    private static final SqlReviewRuleConfigView RULE =
            new SqlReviewRuleConfigView("select_star", SqlReviewSeverity.WARN, Map.of());

    @Test
    void ruleConfigViewCopiesParamsAndDefaultsNullToEmpty() {
        var params = new HashMap<String, String>();
        params.put("names", "pg_sleep,sleep");
        var view = new SqlReviewRuleConfigView("disallowed_function", SqlReviewSeverity.BLOCK, params);
        params.put("later", "x");

        assertThat(view.params()).containsOnlyKeys("names");
        assertThatThrownBy(() -> view.params().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new SqlReviewRuleConfigView("r", SqlReviewSeverity.OFF, null).params()).isEmpty();
    }

    @Test
    void rulesetViewCopiesRulesAndDefaultsNullToEmpty() {
        var rules = new ArrayList<SqlReviewRuleConfigView>();
        rules.add(RULE);
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var now = Instant.now();
        var view = new SqlReviewRulesetView(id, orgId, "prod", "desc", DatasourceEnvironment.PRODUCTION,
                true, rules, now, now);
        rules.add(RULE);

        assertThat(view.id()).isEqualTo(id);
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.name()).isEqualTo("prod");
        assertThat(view.description()).isEqualTo("desc");
        assertThat(view.environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        assertThat(view.enabled()).isTrue();
        assertThat(view.rules()).hasSize(1);
        assertThat(view.createdAt()).isEqualTo(now);
        assertThat(view.updatedAt()).isEqualTo(now);
        assertThat(new SqlReviewRulesetView(id, orgId, "default", null, null, false, null, now, now).rules())
                .isEmpty();
    }

    @Test
    void createCommandCopiesRulesAndDefaultsNullToEmpty() {
        var rules = new ArrayList<SqlReviewRuleConfigView>();
        rules.add(RULE);
        var command = new CreateSqlReviewRulesetCommand("prod", null, DatasourceEnvironment.PRODUCTION, null, rules);
        rules.add(RULE);

        assertThat(command.rules()).hasSize(1);
        assertThat(command.enabled()).isNull();
        assertThat(new CreateSqlReviewRulesetCommand("default", null, null, true, null).rules()).isEmpty();
    }

    @Test
    void updateCommandKeepsNullRulesAsUnchangedAndCopiesNonNull() {
        var rules = new ArrayList<SqlReviewRuleConfigView>();
        rules.add(RULE);
        var replace = new UpdateSqlReviewRulesetCommand(null, null, null, true, null, rules);
        rules.add(RULE);
        var unchanged = new UpdateSqlReviewRulesetCommand("renamed", null, null, null, null, null);

        assertThat(replace.rules()).hasSize(1);
        assertThat(replace.clearEnvironment()).isTrue();
        assertThat(unchanged.rules()).isNull();
        assertThat(unchanged.name()).isEqualTo("renamed");
        assertThat(List.copyOf(replace.rules())).isEqualTo(replace.rules());
    }
}
