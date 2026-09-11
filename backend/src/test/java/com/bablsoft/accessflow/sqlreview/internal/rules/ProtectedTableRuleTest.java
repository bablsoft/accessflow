package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;

class ProtectedTableRuleTest {

    private final ProtectedTableRule rule = new ProtectedTableRule();

    @Test
    void describesItselfWithARequiredGlobsParam() {
        assertThat(rule.ruleId()).isEqualTo("protected_table");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.DATA_PROTECTION);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(rule.messageArgKeys()).containsExactly("table", "glob");
        assertThat(rule.params()).hasSize(1);
        assertThat(rule.params().get(0).key()).isEqualTo("globs");
        assertThat(rule.params().get(0).required()).isTrue();
        assertThat(rule.params().get(0).defaults()).isEmpty();
    }

    @Test
    void absentOrEmptyGlobsNeverFire() {
        assertThat(apply(rule, "SELECT * FROM payroll.salaries")).isEmpty();
        assertThat(rule.apply(context("SELECT * FROM payroll.salaries"), null)).isEmpty();
        assertThat(rule.apply(context("SELECT * FROM payroll.salaries"), Map.of("globs", List.of()))).isEmpty();
    }

    @Test
    void firesOncePerMatchedTableWithTheFirstMatchingGlob() {
        // "*.audit_log" needs a schema, so the bare "Audit_Log" is the first glob to match audit_log.
        var params = Map.of("globs", List.of("payroll.*", "*.audit_log", "Audit_Log"));
        var findings = rule.apply(context("SELECT s.amount\nFROM \"Payroll\".Salaries s\nJOIN audit_log a ON a.id = s.id"),
                params);
        assertThat(findings).hasSize(2);
        assertThat(findings).extracting(f -> f.args())
                .containsExactlyInAnyOrder(Map.of("table", "audit_log", "glob", "Audit_Log"),
                        Map.of("table", "payroll.salaries", "glob", "payroll.*"));
        assertThat(findings).extracting(f -> f.lineNumber()).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void bareGlobMatchesSchemaQualifiedTableAndSubqueriesCount() {
        var params = Map.of("globs", List.of("audit_log"));
        assertThat(rule.apply(context("DELETE FROM public.audit_log"), params)).hasSize(1);
        assertThat(rule.apply(context("SELECT 1 FROM t WHERE EXISTS (SELECT 1 FROM audit_log)"), params)).hasSize(1);
        assertThat(rule.apply(context("SELECT 1 FROM t"), params)).isEmpty();
        assertThat(rule.apply(context("SELECT 1 FROM t"), Map.of("globs", java.util.Arrays.asList(" ", null, "t")))).hasSize(1);
    }
}
