package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewRulesetException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SqlRuleParamsValidatorTest {

    private final SqlRuleParamsValidator validator;

    SqlRuleParamsValidatorTest() {
        var messages = new StaticMessageSource();
        var locale = Locale.getDefault();
        messages.addMessage("error.sql_review_rule_unknown", locale, "unknown {0}");
        messages.addMessage("error.sql_review_rule_param_list_required", locale, "list {0} of {1}");
        messages.addMessage("error.sql_review_rule_params_unexpected", locale, "unexpected {0}");
        messages.addMessage("error.sql_review_rule_glob_invalid", locale, "glob {0}");
        messages.addMessage("error.sql_review_rule_function_invalid", locale, "function {0}");
        validator = new SqlRuleParamsValidator(new SqlRuleCatalog(), messages);
    }

    private static SqlReviewRuleConfigView config(String ruleId, Map<String, List<String>> params) {
        return new SqlReviewRuleConfigView(ruleId, SqlReviewSeverity.WARN, params);
    }

    @Test
    void acceptsWellFormedRulesetsAndNull() {
        assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(List.of(
                config("select_star", Map.of()),
                config("disallowed_function", Map.of()),
                config("disallowed_function", Map.of("names", List.of("pg_sleep", "dbms_lock.sleep"))),
                config("protected_table", Map.of("globs", List.of("payroll.*", "*.audit_log", "hr.$tmp"))))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownRuleIds() {
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> validator.validate(List.of(config("nope", Map.of()))))
                .withMessage("unknown nope");
    }

    @Test
    void rejectsParamsOnParameterlessRulesAndUndeclaredKeys() {
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> validator.validate(List.of(config("select_star", Map.of("names", List.of("x"))))))
                .withMessage("unexpected select_star");
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> validator.validate(List.of(config("protected_table",
                        Map.of("globs", List.of("a"), "extra", List.of("b"))))))
                .withMessage("unexpected protected_table");
    }

    @Test
    void requiredListWithoutDefaultsMustBePresentAndNonBlank() {
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> validator.validate(List.of(config("protected_table", Map.of()))))
                .withMessage("list globs of protected_table");
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> validator.validate(List.of(config("protected_table", Map.of("globs", List.of())))))
                .withMessage("list globs of protected_table");
        var withBlank = new HashMap<String, List<String>>();
        withBlank.put("names", Arrays.asList("sleep", " "));
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> validator.validate(List.of(config("disallowed_function", withBlank))))
                .withMessage("list names of disallowed_function");
    }

    @Test
    void rejectsMalformedGlobsAndFunctionNames() {
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> validator.validate(List.of(config("protected_table",
                        Map.of("globs", List.of("payroll.*", "hr; drop"))))))
                .withMessage("glob hr; drop");
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> validator.validate(List.of(config("disallowed_function",
                        Map.of("names", List.of("pg_sleep", "sleep()"))))))
                .withMessage("function sleep()");
    }
}
