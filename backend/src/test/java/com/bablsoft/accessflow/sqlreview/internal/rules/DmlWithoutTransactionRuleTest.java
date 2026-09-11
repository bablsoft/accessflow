package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.apply;
import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.envelope;
import static org.assertj.core.api.Assertions.assertThat;

class DmlWithoutTransactionRuleTest {

    private final DmlWithoutTransactionRule rule = new DmlWithoutTransactionRule();

    @Test
    void describesItself() {
        assertThat(rule.ruleId()).isEqualTo("dml_without_transaction");
        assertThat(rule.category()).isEqualTo(SqlRuleCategory.STATEMENT_SAFETY);
        assertThat(rule.defaultSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(rule.messageArgKeys()).isEmpty();
    }

    @Test
    void firesOnBareDml() {
        assertThat(apply(rule, "INSERT INTO t VALUES (1)")).hasSize(1);
        assertThat(apply(rule, "UPDATE t SET a = 1 WHERE id = 1")).hasSize(1);
        var delete = apply(rule, "DELETE FROM\n t WHERE id = 1");
        assertThat(delete).hasSize(1);
        assertThat(delete.get(0).lineNumber()).isEqualTo(2);
    }

    @Test
    void doesNotFireInsideAnEnvelopeOrOnNonDml() {
        var inEnvelope = rule.apply(envelope(1, "DELETE FROM t WHERE id = 1"), Map.of());
        assertThat(inEnvelope).isEmpty();
        assertThat(apply(rule, "SELECT a FROM t")).isEmpty();
        assertThat(apply(rule, "TRUNCATE TABLE t")).isEmpty();
    }
}
