package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.util.List;
import java.util.Set;

/**
 * The criteria of a custom SQL review rule (#1009): a typed condition tree over facts derived from
 * one parsed statement. A peer of {@code workflow.api.ConditionNode}, not a reuse of it —
 * {@code workflow} already depends on {@code sqlreview.api}, so the reverse import would be a module
 * cycle. Wire names match the routing tree where the concept is the same. Every leaf is a pure
 * function of the statement AST: no schema introspection, no runtime context.
 */
public sealed interface SqlRuleCondition {

    /** True iff every child matches. */
    record And(List<SqlRuleCondition> children) implements SqlRuleCondition {
        public And {
            children = List.copyOf(children == null ? List.of() : children);
        }
    }

    /** True iff any child matches. */
    record Or(List<SqlRuleCondition> children) implements SqlRuleCondition {
        public Or {
            children = List.copyOf(children == null ? List.of() : children);
        }
    }

    /** Negates its child. */
    record Not(SqlRuleCondition child) implements SqlRuleCondition {
        public Not {
            if (child == null) {
                throw new IllegalArgumentException("Not condition requires a child");
            }
        }
    }

    /** Matches when the statement kind is one of {@code anyOf}. */
    record QueryTypeIn(Set<QueryType> anyOf) implements SqlRuleCondition {
        public QueryTypeIn {
            anyOf = Set.copyOf(anyOf == null ? Set.of() : anyOf);
        }
    }

    /**
     * Matches when any referenced table matches any glob, tried against the normalised
     * {@code schema.table} name and the bare table name.
     */
    record ReferencedTableMatches(List<String> globs) implements SqlRuleCondition {
        public ReferencedTableMatches {
            globs = List.copyOf(globs == null ? List.of() : globs);
        }
    }

    /**
     * Matches when any referenced column matches any glob, tried against the column as written
     * ({@code o.email}) and its bare name ({@code email}).
     */
    record ReferencedColumnMatches(List<String> globs) implements SqlRuleCondition {
        public ReferencedColumnMatches {
            globs = List.copyOf(globs == null ? List.of() : globs);
        }
    }

    /** Matches when any function named in {@code names} is called (unqualified, case-insensitive). */
    record FunctionCalled(List<String> names) implements SqlRuleCondition {
        public FunctionCalled {
            names = List.copyOf(names == null ? List.of() : names);
        }
    }

    /** Matches when presence of a {@code WHERE} clause equals {@code expected}. */
    record HasWhereClause(boolean expected) implements SqlRuleCondition {
    }

    /** Matches when presence of a row limit ({@code LIMIT}, {@code TOP}, {@code FETCH FIRST}) equals {@code expected}. */
    record HasLimitClause(boolean expected) implements SqlRuleCondition {
    }

    /** Matches when presence of an {@code ORDER BY} equals {@code expected}. */
    record HasOrderBy(boolean expected) implements SqlRuleCondition {
    }

    /** Matches when "the {@code WHERE} is a tautology" equals {@code expected}. */
    record WhereAlwaysTrue(boolean expected) implements SqlRuleCondition {
    }

    /** Matches when "the statement contains a Cartesian join" equals {@code expected}. */
    record JoinWithoutCondition(boolean expected) implements SqlRuleCondition {
    }

    /** Matches when "the statement contains a {@code LIKE '%…'}" equals {@code expected}. */
    record LikeLeadingWildcard(boolean expected) implements SqlRuleCondition {
    }

    /** Matches when the {@code BEGIN…COMMIT} envelope flag equals {@code expected}. */
    record Transactional(boolean expected) implements SqlRuleCondition {
    }

    /**
     * The regex escape hatch: matches when {@code pattern} is found anywhere in the statement's
     * normalised text (deparsed, so comments are gone and whitespace is collapsed).
     */
    record SqlMatches(String pattern, boolean ignoreCase) implements SqlRuleCondition {
    }
}
