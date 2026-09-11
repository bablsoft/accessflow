package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleCatalogService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleParamView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleView;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Projects {@link SqlRuleCatalog} onto the localized catalog view the admin UI renders its severity
 * table from (#863). Name and description come from {@code sqlreview.rule.<id>.name} /
 * {@code .description}; a missing key falls back to the key itself rather than failing the whole
 * catalog.
 */
@Service
public class DefaultSqlReviewRuleCatalogService implements SqlReviewRuleCatalogService {

    private final SqlRuleCatalog catalog;
    private final MessageSource messageSource;

    public DefaultSqlReviewRuleCatalogService(SqlRuleCatalog catalog, MessageSource messageSource) {
        this.catalog = catalog;
        this.messageSource = messageSource;
    }

    @Override
    public List<SqlReviewRuleView> rules(Locale locale) {
        return catalog.rules().stream().map(rule -> toView(rule, locale)).toList();
    }

    private SqlReviewRuleView toView(SqlRule rule, Locale locale) {
        var params = rule.params().stream()
                .map(param -> new SqlReviewRuleParamView(param.key(), param.required(), param.defaults(),
                        param.valuePattern().pattern()))
                .toList();
        return new SqlReviewRuleView(rule.ruleId(), rule.category(), rule.defaultSeverity(),
                resolve("sqlreview.rule." + rule.ruleId() + ".name", locale),
                resolve("sqlreview.rule." + rule.ruleId() + ".description", locale),
                params);
    }

    private String resolve(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale);
    }
}
