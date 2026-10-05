package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewCustomRuleException;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Create-time validation of a custom rule's condition tree and message (#1009), so a malformed
 * rule is a 422 when written rather than a silently-skipped rule at evaluation time. Limits: depth
 * ≤ {@value #MAX_DEPTH} (the root is depth 1), ≤ {@value #MAX_LEAVES} leaves, every list operand
 * non-empty, globs and function names in the same syntax the built-in params accept, a regex ≤
 * {@value SafeRegex#MAX_PATTERN_LENGTH} chars that compiles, a non-blank message ≤
 * {@value #MAX_MESSAGE_LENGTH} chars. Messages are resolved in the caller's locale at the throw
 * site, like {@code SqlRuleParamsValidator}.
 */
@Component
public class SqlRuleConditionValidator {

    public static final int MAX_DEPTH = 5;
    public static final int MAX_LEAVES = 20;
    public static final int MAX_MESSAGE_LENGTH = 500;

    /** Same syntax as {@code protected_table.globs}. */
    private static final Pattern GLOB = Pattern.compile("[A-Za-z0-9_$*.\\-]+");
    /** Same syntax as {@code disallowed_function.names}. */
    private static final Pattern FUNCTION = Pattern.compile("[A-Za-z0-9_$.\\-]+");

    private final MessageSource messageSource;

    public SqlRuleConditionValidator(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** Validates a whole rule draft: its condition and its message. */
    public void validate(SqlRuleCondition condition, String message) {
        validateCondition(condition);
        validateMessage(message);
    }

    public void validateCondition(SqlRuleCondition condition) {
        if (condition == null) {
            throw fail("error.sql_review_rule_condition_required");
        }
        int leaves = walk(condition, 1);
        if (leaves > MAX_LEAVES) {
            throw fail("error.sql_review_rule_condition_too_many_leaves", MAX_LEAVES);
        }
    }

    public void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw fail("error.sql_review_rule_message_required");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw fail("error.sql_review_rule_message_too_long", MAX_MESSAGE_LENGTH);
        }
    }

    /** Validates {@code node} at {@code depth} and returns the number of leaves beneath it. */
    private int walk(SqlRuleCondition node, int depth) {
        if (depth > MAX_DEPTH) {
            throw fail("error.sql_review_rule_condition_too_deep", MAX_DEPTH);
        }
        return switch (node) {
            case SqlRuleCondition.And and -> children("and", and.children(), depth);
            case SqlRuleCondition.Or or -> children("or", or.children(), depth);
            case SqlRuleCondition.Not not -> walk(not.child(), depth + 1);
            case SqlRuleCondition.QueryTypeIn leaf -> {
                requireNonEmpty("query_type", leaf.anyOf());
                yield 1;
            }
            case SqlRuleCondition.ReferencedTableMatches leaf -> {
                requireValues("referenced_table", leaf.globs(), GLOB, "error.sql_review_rule_glob_invalid");
                yield 1;
            }
            case SqlRuleCondition.ReferencedColumnMatches leaf -> {
                requireValues("referenced_column", leaf.globs(), GLOB, "error.sql_review_rule_column_glob_invalid");
                yield 1;
            }
            case SqlRuleCondition.FunctionCalled leaf -> {
                requireValues("function_called", leaf.names(), FUNCTION, "error.sql_review_rule_function_invalid");
                yield 1;
            }
            case SqlRuleCondition.SqlMatches leaf -> {
                validateRegex(leaf.pattern());
                yield 1;
            }
            case SqlRuleCondition.HasWhereClause ignored -> 1;
            case SqlRuleCondition.HasLimitClause ignored -> 1;
            case SqlRuleCondition.HasOrderBy ignored -> 1;
            case SqlRuleCondition.WhereAlwaysTrue ignored -> 1;
            case SqlRuleCondition.JoinWithoutCondition ignored -> 1;
            case SqlRuleCondition.LikeLeadingWildcard ignored -> 1;
            case SqlRuleCondition.Transactional ignored -> 1;
        };
    }

    private int children(String type, List<SqlRuleCondition> children, int depth) {
        requireNonEmpty(type, children);
        int leaves = 0;
        for (SqlRuleCondition child : children) {
            leaves += walk(child, depth + 1);
            if (leaves > MAX_LEAVES) {
                throw fail("error.sql_review_rule_condition_too_many_leaves", MAX_LEAVES);
            }
        }
        return leaves;
    }

    private void requireNonEmpty(String type, Collection<?> values) {
        if (values.isEmpty()) {
            throw fail("error.sql_review_rule_condition_empty_list", type);
        }
    }

    private void requireValues(String type, List<String> values, Pattern syntax, String invalidKey) {
        requireNonEmpty(type, values);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw fail("error.sql_review_rule_condition_empty_list", type);
            }
            if (!syntax.matcher(value.trim()).matches()) {
                throw fail(invalidKey, value.trim());
            }
        }
    }

    private void validateRegex(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw fail("error.sql_review_rule_regex_invalid", "");
        }
        if (pattern.length() > SafeRegex.MAX_PATTERN_LENGTH) {
            throw fail("error.sql_review_rule_regex_too_long", SafeRegex.MAX_PATTERN_LENGTH);
        }
        try {
            SafeRegex.compile(pattern, false);
        } catch (PatternSyntaxException ex) {
            throw fail("error.sql_review_rule_regex_invalid", ex.getDescription());
        }
    }

    private IllegalSqlReviewCustomRuleException fail(String key, Object... args) {
        return new IllegalSqlReviewCustomRuleException(
                messageSource.getMessage(key, args, LocaleContextHolder.getLocale()));
    }
}
