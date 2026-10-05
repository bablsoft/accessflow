package com.bablsoft.accessflow.sqlreview.internal.rules.condition;

import com.bablsoft.accessflow.core.api.GlobMatcher;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import com.bablsoft.accessflow.sqlreview.internal.rules.StatementFacts;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Evaluates one custom rule's condition tree against a statement's {@link StatementFacts} (#1009).
 * Built once per rule: every {@code sql_matches} pattern is compiled up front. The {@code switch}
 * is exhaustive with no {@code default}, so a new {@link SqlRuleCondition} variant is a compile
 * error here rather than a silently non-matching leaf. Globs are tried against the full and the
 * bare (after the last dot) name, like {@code protected_table}; function names compare on the
 * unqualified, lower-cased name, like {@code disallowed_function}.
 */
public final class SqlRuleConditionEvaluator {

    private final SqlRuleCondition condition;
    private final Map<SqlRuleCondition.SqlMatches, Pattern> patterns = new HashMap<>();

    public SqlRuleConditionEvaluator(SqlRuleCondition condition) {
        this.condition = condition;
        compile(condition);
    }

    public boolean matches(StatementFacts facts) {
        return matches(condition, facts);
    }

    private boolean matches(SqlRuleCondition node, StatementFacts facts) {
        return switch (node) {
            case SqlRuleCondition.And and -> and.children().stream().allMatch(child -> matches(child, facts));
            case SqlRuleCondition.Or or -> or.children().stream().anyMatch(child -> matches(child, facts));
            case SqlRuleCondition.Not not -> !matches(not.child(), facts);
            case SqlRuleCondition.QueryTypeIn leaf -> leaf.anyOf().contains(facts.queryType());
            case SqlRuleCondition.ReferencedTableMatches leaf -> anyGlobMatches(leaf.globs(), facts.tables());
            case SqlRuleCondition.ReferencedColumnMatches leaf -> anyGlobMatches(leaf.globs(), facts.columns());
            case SqlRuleCondition.FunctionCalled leaf -> leaf.names().stream()
                    .map(SqlRuleConditionEvaluator::functionName)
                    .anyMatch(facts.functions()::contains);
            case SqlRuleCondition.HasWhereClause leaf -> facts.hasWhere() == leaf.expected();
            case SqlRuleCondition.HasLimitClause leaf -> facts.hasLimit() == leaf.expected();
            case SqlRuleCondition.HasOrderBy leaf -> facts.hasOrderBy() == leaf.expected();
            case SqlRuleCondition.WhereAlwaysTrue leaf -> facts.whereAlwaysTrue() == leaf.expected();
            case SqlRuleCondition.JoinWithoutCondition leaf -> facts.joinWithoutCondition() == leaf.expected();
            case SqlRuleCondition.LikeLeadingWildcard leaf -> facts.leadingWildcardLike() == leaf.expected();
            case SqlRuleCondition.Transactional leaf -> facts.transactional() == leaf.expected();
            case SqlRuleCondition.SqlMatches leaf -> SafeRegex.find(patterns.get(leaf), facts.normalizedText());
        };
    }

    private void compile(SqlRuleCondition node) {
        switch (node) {
            case SqlRuleCondition.And and -> and.children().forEach(this::compile);
            case SqlRuleCondition.Or or -> or.children().forEach(this::compile);
            case SqlRuleCondition.Not not -> compile(not.child());
            case SqlRuleCondition.SqlMatches leaf ->
                    patterns.computeIfAbsent(leaf, key -> SafeRegex.compile(key.pattern(), key.ignoreCase()));
            default -> { /* no pattern to compile */ }
        }
    }

    private static boolean anyGlobMatches(List<String> globs, Collection<String> names) {
        for (String name : names) {
            var bare = bareName(name);
            for (String glob : globs) {
                if (glob != null && !glob.isBlank()
                        && (GlobMatcher.matches(glob.trim(), name) || GlobMatcher.matches(glob.trim(), bare))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String functionName(String configured) {
        return bareName(configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT));
    }

    private static String bareName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
