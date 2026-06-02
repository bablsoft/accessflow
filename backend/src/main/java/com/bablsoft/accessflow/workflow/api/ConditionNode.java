package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Typed, attribute-based condition tree for a {@code routing_policy}. A pure value model — it draws
 * only on signals AccessFlow already computes (no external policy engine, no third-party imports),
 * so it satisfies the module-API-purity rule. Evaluated against a {@link ConditionContext} by
 * {@code RoutingConditionEvaluator} (workflow.internal); (de)serialized to the {@code condition}
 * JSONB column by {@code RoutingConditionCodec}.
 *
 * <p>Combinators ({@link And}, {@link Or}, {@link Not}) compose leaf comparators, one per signal:
 * query type, referenced tables (glob), AI risk level / score, requester role / group membership,
 * time-of-day / day-of-week, presence of WHERE / LIMIT, and the transactional flag.
 */
public sealed interface ConditionNode {

    /** True iff every child matches (an empty list is vacuously true). */
    record And(List<ConditionNode> children) implements ConditionNode {
        public And {
            children = List.copyOf(children == null ? List.of() : children);
        }
    }

    /** True iff any child matches (an empty list is vacuously false). */
    record Or(List<ConditionNode> children) implements ConditionNode {
        public Or {
            children = List.copyOf(children == null ? List.of() : children);
        }
    }

    /** Negates its child. */
    record Not(ConditionNode child) implements ConditionNode {
        public Not {
            if (child == null) {
                throw new IllegalArgumentException("Not condition requires a child");
            }
        }
    }

    /** Matches when the query type is one of {@code anyOf}. */
    record QueryTypeIn(Set<QueryType> anyOf) implements ConditionNode {
        public QueryTypeIn {
            anyOf = Set.copyOf(anyOf == null ? Set.of() : anyOf);
        }
    }

    /**
     * Matches when any referenced table matches any glob. Globs use {@code *} as a wildcard that
     * spans any characters (including the {@code schema.table} dot), e.g. {@code payroll.*},
     * {@code *.users}, or the exact {@code public.orders}. Matching is case-insensitive (the parser
     * already normalises table names to lower-case).
     */
    record ReferencedTableMatches(List<String> globs) implements ConditionNode {
        public ReferencedTableMatches {
            globs = List.copyOf(globs == null ? List.of() : globs);
        }
    }

    /** Matches when the AI risk level is one of {@code anyOf}. False when no AI risk signal exists. */
    record RiskLevelIn(Set<RiskLevel> anyOf) implements ConditionNode {
        public RiskLevelIn {
            anyOf = Set.copyOf(anyOf == null ? Set.of() : anyOf);
        }
    }

    /** Compares the AI risk score (0–100) with {@code value}. False when no AI risk signal exists. */
    record RiskScore(ComparisonOperator operator, int value) implements ConditionNode {
        public RiskScore {
            if (operator == null) {
                throw new IllegalArgumentException("RiskScore condition requires an operator");
            }
        }
    }

    /** Matches when the requester's role is one of {@code anyOf}. */
    record RequesterRoleIn(Set<UserRoleType> anyOf) implements ConditionNode {
        public RequesterRoleIn {
            anyOf = Set.copyOf(anyOf == null ? Set.of() : anyOf);
        }
    }

    /** Matches when the requester belongs to any of {@code groupIds}. */
    record RequesterInGroup(Set<UUID> groupIds) implements ConditionNode {
        public RequesterInGroup {
            groupIds = Set.copyOf(groupIds == null ? Set.of() : groupIds);
        }
    }

    /**
     * Matches when the evaluation time-of-day falls within the inclusive minute-of-day window
     * {@code [startMinuteOfDay, endMinuteOfDay]}. When {@code start > end} the window wraps past
     * midnight (e.g. 22:00–06:00 = after-hours). Evaluated in the server's local zone.
     */
    record TimeOfDay(int startMinuteOfDay, int endMinuteOfDay) implements ConditionNode {
        public TimeOfDay {
            if (startMinuteOfDay < 0 || startMinuteOfDay > 1439
                    || endMinuteOfDay < 0 || endMinuteOfDay > 1439) {
                throw new IllegalArgumentException(
                        "TimeOfDay minutes must be in [0, 1439]");
            }
        }
    }

    /** Matches when the evaluation day-of-week is one of {@code anyOf}. */
    record DayOfWeekIn(Set<DayOfWeek> anyOf) implements ConditionNode {
        public DayOfWeekIn {
            anyOf = Set.copyOf(anyOf == null ? Set.of() : anyOf);
        }
    }

    /** Matches when presence of a WHERE clause equals {@code expected}. */
    record HasWhereClause(boolean expected) implements ConditionNode {
    }

    /** Matches when presence of a LIMIT clause equals {@code expected}. */
    record HasLimitClause(boolean expected) implements ConditionNode {
    }

    /** Matches when the transactional ({@code BEGIN…COMMIT}) flag equals {@code expected}. */
    record Transactional(boolean expected) implements ConditionNode {
    }
}
