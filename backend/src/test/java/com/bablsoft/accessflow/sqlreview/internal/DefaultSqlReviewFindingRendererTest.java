package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSqlReviewFindingRendererTest {

    private final StaticMessageSource messages = new StaticMessageSource();
    private final DefaultSqlReviewFindingRenderer renderer =
            new DefaultSqlReviewFindingRenderer(new SqlRuleCatalog(), messages);

    @Test
    void bindsArgsInTheRuleDeclaredOrder() {
        messages.addMessage("sqlreview.rule.protected_table.message", Locale.ENGLISH, "{0} matches {1}");
        messages.addMessage("sqlreview.rule.protected_table.message", Locale.GERMAN, "{0} entspricht {1}");
        var finding = new SqlReviewFinding("protected_table", SqlReviewSeverity.BLOCK, 0, 1,
                Map.of("glob", "payroll.*", "table", "payroll.salaries"));

        assertThat(renderer.message(finding, Locale.ENGLISH)).isEqualTo("payroll.salaries matches payroll.*");
        assertThat(renderer.message(finding, Locale.GERMAN)).isEqualTo("payroll.salaries entspricht payroll.*");
    }

    @Test
    void argLessRuleRendersItsMessageAndMissingArgsRenderEmpty() {
        messages.addMessage("sqlreview.rule.select_star.message", Locale.ENGLISH, "SELECT * found");
        messages.addMessage("sqlreview.rule.truncate_statement.message", Locale.ENGLISH, "TRUNCATE [{0}]");

        assertThat(renderer.message(new SqlReviewFinding("select_star", SqlReviewSeverity.WARN, 0, null, Map.of()),
                Locale.ENGLISH)).isEqualTo("SELECT * found");
        assertThat(renderer.message(new SqlReviewFinding("truncate_statement", SqlReviewSeverity.BLOCK, 0, null,
                Map.of()), Locale.ENGLISH)).isEqualTo("TRUNCATE []");
    }

    @Test
    void unknownRuleIdRendersAsItself() {
        var finding = new SqlReviewFinding("retired_rule", SqlReviewSeverity.WARN, 0, null, Map.of());

        assertThat(renderer.message(finding, Locale.ENGLISH)).isEqualTo("retired_rule");
    }

    @Test
    void missingMessageKeyFallsBackToTheRuleId() {
        var finding = new SqlReviewFinding("cross_join", SqlReviewSeverity.WARN, 0, 3, Map.of("table", "t"));

        assertThat(renderer.message(finding, Locale.FRENCH)).isEqualTo("cross_join");
    }
}
