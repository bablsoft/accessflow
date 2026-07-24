package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;

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
 * time-of-day / day-of-week, presence of WHERE / LIMIT, the transactional flag, and the client
 * context captured at submission — source IP (CIDR), user-agent (glob), time-since-last-approval,
 * and CI/CD origin. The client-context leaves <strong>fail closed</strong>: when the required
 * signal is absent (no IP / user-agent / prior approval) the leaf evaluates to {@code false}, so a
 * permissive {@code AUTO_APPROVE} policy keyed on a positive match never fires on missing context.
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

    /**
     * Matches when the requester's role NAME is one of {@code anyOf} — a system-role name or a
     * custom role's name, compared case-insensitively (AF-522).
     */
    record RequesterRoleIn(Set<String> anyOf) implements ConditionNode {
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

    /**
     * Matches when the requester's submission source IP falls within any of the {@code cidrs}
     * (IPv4 or IPv6, {@code address/prefix}). For an allow-list use it directly; for a deny-list /
     * escalation wrap it in {@link Not}. <strong>Fails closed</strong>: evaluates to {@code false}
     * when the query carries no source IP (captured at submission), so a permissive policy never
     * fires on missing context — and {@code Not(SourceIpMatches(...))} stays {@code true}.
     * Each entry's CIDR syntax is validated when the policy is created / updated.
     */
    record SourceIpMatches(List<String> cidrs) implements ConditionNode {
        public SourceIpMatches {
            cidrs = List.copyOf(cidrs == null ? List.of() : cidrs);
        }
    }

    /**
     * Matches when the requester's submission user-agent matches any glob in {@code patterns}
     * ({@code *} = any run of characters, case-insensitive — same matcher as referenced tables).
     * <strong>Fails closed</strong>: evaluates to {@code false} when the query carries no
     * user-agent.
     */
    record UserAgentMatches(List<String> patterns) implements ConditionNode {
        public UserAgentMatches {
            patterns = List.copyOf(patterns == null ? List.of() : patterns);
        }
    }

    /**
     * Compares the minutes elapsed since the requester's last APPROVED / EXECUTED query on the same
     * datasource with {@code minutes}. <strong>Fails closed</strong>: evaluates to {@code false}
     * when the requester has no prior approval on that datasource (no recency signal).
     */
    record TimeSinceLastApproval(ComparisonOperator operator, int minutes) implements ConditionNode {
        public TimeSinceLastApproval {
            if (operator == null) {
                throw new IllegalArgumentException("TimeSinceLastApproval condition requires an operator");
            }
            if (minutes < 0) {
                throw new IllegalArgumentException("TimeSinceLastApproval minutes must be >= 0");
            }
        }
    }

    /**
     * Matches when the request's CI/CD origin flag equals {@code expected}. A query is flagged as
     * CI/CD origin when it was submitted via an AccessFlow API key or carried the
     * {@code X-AccessFlow-CI} header — captured at submission. Deterministic (no missing-context
     * case): the flag is always present, defaulting to {@code false}.
     */
    record CiCdOrigin(boolean expected) implements ConditionNode {
    }

    /**
     * Compares the pre-flight estimated row impact (AF-624 — for UPDATE/DELETE the exact
     * affected-row count when available, otherwise the EXPLAIN estimate) with {@code value}.
     * <strong>Fails closed</strong>: evaluates to {@code false} when no estimate signal exists —
     * the estimate has not been computed yet, the engine has no plan concept, or the computation
     * failed — so a permissive policy keyed on a small estimate never fires on missing context.
     */
    record EstimatedRows(ComparisonOperator operator, long value) implements ConditionNode {
        public EstimatedRows {
            if (operator == null) {
                throw new IllegalArgumentException("EstimatedRows condition requires an operator");
            }
            if (value < 0) {
                throw new IllegalArgumentException("EstimatedRows value must be >= 0");
            }
        }
    }

    /**
     * Matches when the pre-flight plan's root scan/operation type (AF-624 — e.g. {@code Seq Scan},
     * {@code Index Scan}, {@code COLLSCAN}) matches any glob in {@code patterns} ({@code *} = any
     * run of characters, case-insensitive — same matcher as referenced tables).
     * <strong>Fails closed</strong>: evaluates to {@code false} when no plan was captured.
     */
    record ScanTypeMatches(List<String> patterns) implements ConditionNode {
        public ScanTypeMatches {
            patterns = List.copyOf(patterns == null ? List.of() : patterns);
        }
    }

    /**
     * Matches when the requester has an active (OPEN) behavioural anomaly on this datasource
     * (UBA, AF-383) equal to {@code expected}. Pair {@code AnomalyDetected(true)} with an
     * {@code ESCALATE} action to force extra approvals on a flagged user's next query. Deterministic
     * (no missing-context case): the flag is always present, defaulting to {@code false}.
     */
    record AnomalyDetected(boolean expected) implements ConditionNode {
    }
}
