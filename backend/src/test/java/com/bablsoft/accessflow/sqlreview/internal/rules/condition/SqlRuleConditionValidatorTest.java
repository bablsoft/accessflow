package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewCustomRuleException;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SqlRuleConditionValidatorTest {

    private static final SqlRuleCondition LEAF = new SqlRuleCondition.HasWhereClause(false);

    private final SqlRuleConditionValidator validator;

    SqlRuleConditionValidatorTest() {
        var messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        validator = new SqlRuleConditionValidator(messages);
    }

    private void rejects(SqlRuleCondition condition, String key) {
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> validator.validateCondition(condition))
                .withMessage(key);
    }

    private static SqlRuleCondition nested(int depth) {
        SqlRuleCondition node = LEAF;
        for (int i = 1; i < depth; i++) {
            node = new SqlRuleCondition.Not(node);
        }
        return node;
    }

    @Test
    void acceptsEveryWellFormedLeaf() {
        assertThatCode(() -> validator.validate(new SqlRuleCondition.Or(List.of(
                new SqlRuleCondition.QueryTypeIn(Set.of(QueryType.DELETE)),
                new SqlRuleCondition.ReferencedTableMatches(List.of("billing.*", "my-table")),
                new SqlRuleCondition.ReferencedColumnMatches(List.of("*.ssn")),
                new SqlRuleCondition.FunctionCalled(List.of("dbms_lock.sleep")),
                new SqlRuleCondition.SqlMatches("drop\\s+table", true),
                new SqlRuleCondition.HasLimitClause(true),
                new SqlRuleCondition.HasOrderBy(true),
                new SqlRuleCondition.WhereAlwaysTrue(true),
                new SqlRuleCondition.JoinWithoutCondition(true),
                new SqlRuleCondition.LikeLeadingWildcard(true),
                new SqlRuleCondition.Transactional(true),
                new SqlRuleCondition.And(List.of(LEAF)))), "Message")).doesNotThrowAnyException();
    }

    @Test
    void requiresACondition() {
        rejects(null, "error.sql_review_rule_condition_required");
    }

    @Test
    void capsDepthAtFive() {
        assertThatCode(() -> validator.validateCondition(nested(SqlRuleConditionValidator.MAX_DEPTH)))
                .doesNotThrowAnyException();
        rejects(nested(SqlRuleConditionValidator.MAX_DEPTH + 1), "error.sql_review_rule_condition_too_deep");
    }

    @Test
    void capsLeavesAtTwenty() {
        var twenty = new ArrayList<SqlRuleCondition>();
        for (int i = 0; i < SqlRuleConditionValidator.MAX_LEAVES; i++) {
            twenty.add(LEAF);
        }
        assertThatCode(() -> validator.validateCondition(new SqlRuleCondition.And(twenty))).doesNotThrowAnyException();
        var twentyOne = new ArrayList<>(twenty);
        twentyOne.add(LEAF);
        rejects(new SqlRuleCondition.Or(twentyOne), "error.sql_review_rule_condition_too_many_leaves");
        // Spread across two branches, the cap still holds.
        rejects(new SqlRuleCondition.And(List.of(new SqlRuleCondition.Or(twenty), LEAF)),
                "error.sql_review_rule_condition_too_many_leaves");
    }

    @Test
    void requiresNonEmptyListsAndChildren() {
        rejects(new SqlRuleCondition.And(List.of()), "error.sql_review_rule_condition_empty_list");
        rejects(new SqlRuleCondition.Or(List.of()), "error.sql_review_rule_condition_empty_list");
        rejects(new SqlRuleCondition.QueryTypeIn(Set.of()), "error.sql_review_rule_condition_empty_list");
        rejects(new SqlRuleCondition.ReferencedTableMatches(List.of()), "error.sql_review_rule_condition_empty_list");
        rejects(new SqlRuleCondition.ReferencedColumnMatches(List.of()), "error.sql_review_rule_condition_empty_list");
        rejects(new SqlRuleCondition.FunctionCalled(List.of()), "error.sql_review_rule_condition_empty_list");
        rejects(new SqlRuleCondition.FunctionCalled(List.of(" ")), "error.sql_review_rule_condition_empty_list");
    }

    @Test
    void rejectsMalformedGlobsAndFunctionNames() {
        rejects(new SqlRuleCondition.ReferencedTableMatches(List.of("bad glob")), "error.sql_review_rule_glob_invalid");
        rejects(new SqlRuleCondition.ReferencedColumnMatches(List.of("a;b")), "error.sql_review_rule_column_glob_invalid");
        rejects(new SqlRuleCondition.FunctionCalled(List.of("sleep()")), "error.sql_review_rule_function_invalid");
    }

    @Test
    void rejectsMissingOverlongAndUncompilableRegex() {
        rejects(new SqlRuleCondition.SqlMatches(null, false), "error.sql_review_rule_regex_invalid");
        rejects(new SqlRuleCondition.SqlMatches("", false), "error.sql_review_rule_regex_invalid");
        rejects(new SqlRuleCondition.SqlMatches("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1), false),
                "error.sql_review_rule_regex_too_long");
        rejects(new SqlRuleCondition.SqlMatches("(unclosed", false), "error.sql_review_rule_regex_invalid");
    }

    @Test
    void validatesTheMessage() {
        assertThatCode(() -> validator.validateMessage("x".repeat(SqlRuleConditionValidator.MAX_MESSAGE_LENGTH)))
                .doesNotThrowAnyException();
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> validator.validateMessage(null)).withMessage("error.sql_review_rule_message_required");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> validator.validateMessage("  ")).withMessage("error.sql_review_rule_message_required");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> validator.validateMessage("x".repeat(SqlRuleConditionValidator.MAX_MESSAGE_LENGTH + 1)))
                .withMessage("error.sql_review_rule_message_too_long");
        assertThatExceptionOfType(IllegalSqlReviewCustomRuleException.class)
                .isThrownBy(() -> validator.validate(LEAF, "")).withMessage("error.sql_review_rule_message_required");
    }
}
