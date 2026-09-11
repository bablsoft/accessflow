package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlRuleContextTest {

    @Test
    void ofIsAVerbatimSingleStatement() {
        var context = SqlRuleContext.of(parse("SELECT 1"));
        assertThat(context.statementIndex()).isZero();
        assertThat(context.transactional()).isFalse();
        assertThat(context.lineNumbersKnown()).isTrue();
    }

    @Test
    void rejectsNullStatement() {
        assertThatThrownBy(() -> new SqlRuleContext(0, null, false, true))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("statement");
    }

    @Test
    void lineOfReadsTheFirstTokenOrYieldsNull() {
        var select = parse("SELECT a\nFROM\n  t");
        var context = SqlRuleContext.of(select);
        var table = ((net.sf.jsqlparser.statement.select.PlainSelect) select).getFromItem(Table.class);
        assertThat(context.lineOf(table)).isEqualTo(3);
        assertThat(context.lineOf(null)).isNull();
        assertThat(context.lineOf(new Table("fresh"))).isNull();
        assertThat(new SqlRuleContext(2, select, true, false).lineOf(table)).isNull();
    }

    @Test
    void findingCarriesRuleIdDefaultSeverityIndexLineAndArgs() {
        var select = parse("SELECT a FROM t");
        var context = new SqlRuleContext(4, select, false, true);
        var rule = new SelectStarRule();
        var finding = context.finding(rule, null, Map.of("k", "v"));
        assertThat(finding.ruleId()).isEqualTo(rule.ruleId());
        assertThat(finding.severity()).isEqualTo(rule.defaultSeverity());
        assertThat(finding.statementIndex()).isEqualTo(4);
        assertThat(finding.lineNumber()).isNull();
        assertThat(finding.args()).isEqualTo(Map.of("k", "v"));
    }
}
