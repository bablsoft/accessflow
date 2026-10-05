package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleCatalogService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleParamView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleView;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.CustomSqlRule;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Projects an organization's {@link SqlRuleSource} onto the catalog view the admin UI renders its
 * severity table from (#863, #1009). A built-in's name and description come from
 * {@code sqlreview.rule.<id>.name} / {@code .description}; a missing key falls back to the key
 * itself rather than failing the whole catalog. A custom rule carries the name and description its
 * author wrote, not localized.
 */
@Service
public class DefaultSqlReviewRuleCatalogService implements SqlReviewRuleCatalogService {

    private final SqlRuleSource ruleSource;
    private final MessageSource messageSource;

    public DefaultSqlReviewRuleCatalogService(SqlRuleSource ruleSource, MessageSource messageSource) {
        this.ruleSource = ruleSource;
        this.messageSource = messageSource;
    }

    @Override
    public List<SqlReviewRuleView> rules(UUID organizationId, Locale locale) {
        return ruleSource.rules(organizationId).stream().map(rule -> toView(rule, locale)).toList();
    }

    private SqlReviewRuleView toView(SqlRule rule, Locale locale) {
        if (rule instanceof CustomSqlRule custom) {
            return new SqlReviewRuleView(custom.ruleId(), custom.category(), custom.defaultSeverity(), custom.name(),
                    custom.description(), List.of(), true);
        }
        var params = rule.params().stream()
                .map(param -> new SqlReviewRuleParamView(param.key(), param.required(), param.defaults(),
                        param.valuePattern().pattern()))
                .toList();
        return new SqlReviewRuleView(rule.ruleId(), rule.category(), rule.defaultSeverity(),
                resolve("sqlreview.rule." + rule.ruleId() + ".name", locale),
                resolve("sqlreview.rule." + rule.ruleId() + ".description", locale),
                params, false);
    }

    private String resolve(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale);
    }
}
