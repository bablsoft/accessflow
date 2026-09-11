package com.bablsoft.accessflow.sqlreview.internal.rules;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fourteen built-in rules in catalog order (#862). The rules are plain classes; this is the
 * one Spring bean that knows them, so the evaluator, the params validator and — later — the
 * catalog endpoint all agree on the set.
 */
@Component
public class SqlRuleCatalog {

    private final Map<String, SqlRule> byId;
    private final List<SqlRule> ordered;

    public SqlRuleCatalog() {
        this(List.of(
                new SelectStarRule(),
                new MissingWhereOnUpdateRule(),
                new MissingWhereOnDeleteRule(),
                new WhereAlwaysTrueRule(),
                new MissingLimitOnSelectRule(),
                new OrderByWithoutLimitRule(),
                new CrossJoinRule(),
                new LeadingWildcardLikeRule(),
                new DropStatementRule(),
                new TruncateStatementRule(),
                new DdlStatementRule(),
                new DisallowedFunctionRule(),
                new ProtectedTableRule(),
                new DmlWithoutTransactionRule()));
    }

    /** Test seam: a catalog over an explicit rule list. Duplicate ids are a programming error. */
    public SqlRuleCatalog(List<SqlRule> rules) {
        var map = new LinkedHashMap<String, SqlRule>();
        for (SqlRule rule : rules) {
            if (map.putIfAbsent(rule.ruleId(), rule) != null) {
                throw new IllegalArgumentException("Duplicate SQL review rule id: " + rule.ruleId());
            }
        }
        this.byId = Map.copyOf(map);
        this.ordered = List.copyOf(map.values());
    }

    public List<SqlRule> rules() {
        return ordered;
    }

    public Optional<SqlRule> byId(String ruleId) {
        return ruleId == null ? Optional.empty() : Optional.ofNullable(byId.get(ruleId));
    }
}
