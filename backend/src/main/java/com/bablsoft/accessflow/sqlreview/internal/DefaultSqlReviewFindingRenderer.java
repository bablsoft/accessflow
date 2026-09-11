package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Binds a finding's {@code args} onto {@code sqlreview.rule.<rule_id>.message} in the order the
 * rule declares through {@link SqlRule#messageArgKeys()} (#863). A finding whose rule id the catalog
 * no longer knows — a persisted row that outlived a catalog change — renders as its rule id rather
 * than failing the reader.
 */
@Component
public class DefaultSqlReviewFindingRenderer implements SqlReviewFindingRenderer {

    private final SqlRuleCatalog catalog;
    private final MessageSource messageSource;

    public DefaultSqlReviewFindingRenderer(SqlRuleCatalog catalog, MessageSource messageSource) {
        this.catalog = catalog;
        this.messageSource = messageSource;
    }

    @Override
    public String message(SqlReviewFinding finding, Locale locale) {
        var rule = catalog.byId(finding.ruleId());
        if (rule.isEmpty()) {
            return finding.ruleId();
        }
        var args = orderedArgs(rule.get().messageArgKeys(), finding);
        var key = "sqlreview.rule." + finding.ruleId() + ".message";
        return messageSource.getMessage(key, args, finding.ruleId(), locale);
    }

    private static Object[] orderedArgs(List<String> keys, SqlReviewFinding finding) {
        var args = new Object[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            var value = finding.args().get(keys.get(i));
            args[i] = value == null ? "" : value;
        }
        return args;
    }
}
