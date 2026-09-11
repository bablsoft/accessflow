package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;

class DisallowedFunctionRuleTest {

    private final DisallowedFunctionRule rule = new DisallowedFunctionRule();

    @Test
    void describesItselfWithDefaultedNamesParam() {
        assertThat(rule.ruleId()).isEqualTo("disallowed_function");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.STATEMENT_SAFETY);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(rule.messageArgKeys()).containsExactly("function");
        assertThat(rule.params()).hasSize(1);
        assertThat(rule.params().get(0).key()).isEqualTo("names");
        assertThat(rule.params().get(0).required()).isTrue();
        assertThat(rule.params().get(0).defaults()).containsExactly("pg_sleep", "sleep", "benchmark", "load_file");
    }

    @Test
    void absentOrEmptyParamsFallBackToTheBuiltInList() {
        var findings = apply(rule, "SELECT a FROM t\nWHERE pg_catalog.PG_SLEEP(5) IS NULL");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).args()).isEqualTo(Map.of("function", "pg_sleep"));
        assertThat(findings.get(0).lineNumber()).isEqualTo(2);
        assertThat(rule.apply(context("SELECT sleep(1)"), null)).hasSize(1);
        assertThat(rule.apply(context("SELECT sleep(1)"), Map.of("names", List.of()))).hasSize(1);
        assertThat(apply(rule, "SELECT benchmark(1000, md5('x'))")).hasSize(1);
        assertThat(apply(rule, "UPDATE t SET a = load_file('/etc/passwd')")).hasSize(1);
        assertThat(apply(rule, "SELECT id FROM t ORDER BY pg_sleep(10)")).hasSize(1);
    }

    @Test
    void configuredNamesReplaceTheDefaults() {
        var params = Map.of("names", List.of("Now", " version "));
        assertThat(rule.apply(context("SELECT now(), version()"), params)).hasSize(2);
        assertThat(rule.apply(context("SELECT pg_sleep(1)"), params)).isEmpty();
        var withBlank = new HashMap<String, List<String>>();
        withBlank.put("names", java.util.Arrays.asList("  ", null, "sleep"));
        assertThat(rule.apply(context("SELECT sleep(1)"), withBlank)).hasSize(1);
    }

    @Test
    void doesNotFireOnAllowedFunctionsOrNestedNonMatches() {
        assertThat(apply(rule, "SELECT count(*), upper(name) FROM t")).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t WHERE id IN (SELECT id FROM u WHERE lower(k) = 'x')")).isEmpty();
        assertThat(apply(rule, "DELETE FROM t")).isEmpty();
    }
}
