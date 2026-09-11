package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewRulesetException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleParam;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates a ruleset's rule configs at save time (#862), so a malformed glob or an empty
 * function list is rejected with 422 when written rather than discovered during evaluation. A
 * required param may be <em>omitted</em> when the rule has built-in defaults, but a list that is
 * supplied must be non-empty and blank-free — {@code {"names": []}} is refused, not silently
 * re-defaulted. Value syntax comes from each {@link SqlRuleParam}, so the validator knows no rule
 * by name. Mirrors {@code workflow.internal.routing.RoutingConditionValidator}: the message is
 * resolved in the caller's locale at the throw site. Wired into ruleset create / update by #863.
 */
@Component
public class SqlRuleParamsValidator {

    private final SqlRuleCatalog catalog;
    private final MessageSource messageSource;

    public SqlRuleParamsValidator(SqlRuleCatalog catalog, MessageSource messageSource) {
        this.catalog = catalog;
        this.messageSource = messageSource;
    }

    public void validate(List<SqlReviewRuleConfigView> rules) {
        if (rules == null) {
            return;
        }
        for (SqlReviewRuleConfigView config : rules) {
            var rule = catalog.byId(config.ruleId())
                    .orElseThrow(() -> fail("error.sql_review_rule_unknown", config.ruleId()));
            validateParams(rule, config.params());
        }
    }

    private void validateParams(SqlRule rule, Map<String, List<String>> params) {
        var declared = rule.params();
        if (declared.isEmpty()) {
            if (!params.isEmpty()) {
                throw fail("error.sql_review_rule_params_unexpected", rule.ruleId());
            }
            return;
        }
        for (SqlRuleParam param : declared) {
            var values = params.get(param.key());
            if (values == null) {
                if (param.required() && param.defaults().isEmpty()) {
                    throw fail("error.sql_review_rule_param_list_required", param.key(), rule.ruleId());
                }
                continue;
            }
            if (values.isEmpty()) {
                throw fail("error.sql_review_rule_param_list_required", param.key(), rule.ruleId());
            }
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    throw fail("error.sql_review_rule_param_list_required", param.key(), rule.ruleId());
                }
                if (!param.valuePattern().matcher(value.trim()).matches()) {
                    throw fail(param.invalidValueKey(), value.trim());
                }
            }
        }
        for (String key : params.keySet()) {
            if (declared.stream().noneMatch(param -> param.key().equals(key))) {
                throw fail("error.sql_review_rule_params_unexpected", rule.ruleId());
            }
        }
    }

    private IllegalSqlReviewRulesetException fail(String key, Object... args) {
        return new IllegalSqlReviewRulesetException(
                messageSource.getMessage(key, args, LocaleContextHolder.getLocale()));
    }
}
