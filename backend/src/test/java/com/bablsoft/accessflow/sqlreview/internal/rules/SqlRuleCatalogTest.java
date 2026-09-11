package com.bablsoft.accessflow.sqlreview.internal.rules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlRuleCatalogTest {

    private final SqlRuleCatalog catalog = new SqlRuleCatalog();

    @Test
    void holdsTheFourteenBuiltInsInOrderWithUniqueIds() {
        assertThat(catalog.rules()).extracting(SqlRule::ruleId).containsExactly(
                "select_star", "missing_where_on_update", "missing_where_on_delete", "where_always_true",
                "missing_limit_on_select", "order_by_without_limit", "cross_join", "leading_wildcard_like",
                "drop_statement", "truncate_statement", "ddl_statement", "disallowed_function",
                "protected_table", "dml_without_transaction");
        assertThat(new HashSet<>(catalog.rules().stream().map(SqlRule::ruleId).toList())).hasSize(14);
    }

    @Test
    void looksUpByIdAndToleratesUnknownOrNull() {
        assertThat(catalog.byId("cross_join")).containsInstanceOf(CrossJoinRule.class);
        assertThat(catalog.byId("nope")).isEmpty();
        assertThat(catalog.byId(null)).isEmpty();
    }

    @Test
    void rejectsDuplicateIds() {
        assertThatThrownBy(() -> new SqlRuleCatalog(List.of(new SelectStarRule(), new SelectStarRule())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("select_star");
    }

    @Test
    void everyRuleHasItsThreeMessageKeysAndMatchingPlaceholderCount() throws IOException {
        var messages = new Properties();
        try (var in = getClass().getResourceAsStream("/i18n/messages.properties")) {
            messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (SqlRule rule : catalog.rules()) {
            var prefix = "sqlreview.rule." + rule.ruleId();
            assertThat(messages).containsKeys(prefix + ".name", prefix + ".description", prefix + ".message");
            var message = messages.getProperty(prefix + ".message");
            for (int i = 0; i < rule.messageArgKeys().size(); i++) {
                assertThat(message).as("%s binds {%d}", prefix, i).contains("{" + i + "}");
            }
            assertThat(message).as("%s has no unbound placeholder", prefix)
                    .doesNotContain("{" + rule.messageArgKeys().size() + "}");
        }
    }

    @Test
    void messageArgKeysMatchTheArgsEachRuleActuallyEmits() {
        var fixtures = Map.ofEntries(
                Map.entry("select_star", "SELECT * FROM t"),
                Map.entry("missing_where_on_update", "UPDATE t SET a = 1"),
                Map.entry("missing_where_on_delete", "DELETE FROM t"),
                Map.entry("where_always_true", "DELETE FROM t WHERE 1 = 1"),
                Map.entry("missing_limit_on_select", "SELECT a FROM t"),
                Map.entry("order_by_without_limit", "SELECT a FROM t ORDER BY a"),
                Map.entry("cross_join", "SELECT a FROM t CROSS JOIN u"),
                Map.entry("leading_wildcard_like", "SELECT a FROM t WHERE b LIKE '%x'"),
                Map.entry("drop_statement", "DROP TABLE t"),
                Map.entry("truncate_statement", "TRUNCATE TABLE t"),
                Map.entry("ddl_statement", "DROP TABLE t"),
                Map.entry("disallowed_function", "SELECT sleep(1)"),
                Map.entry("protected_table", "SELECT a FROM t"),
                Map.entry("dml_without_transaction", "DELETE FROM t"));
        for (SqlRule rule : catalog.rules()) {
            var params = Map.of("globs", List.of("t"));
            var findings = rule.apply(RuleTestSupport.context(fixtures.get(rule.ruleId())), params);
            assertThat(findings).as(rule.ruleId()).isNotEmpty();
            assertThat(findings.get(0).args().keySet()).as(rule.ruleId())
                    .containsExactlyInAnyOrderElementsOf(rule.messageArgKeys());
        }
    }
}
