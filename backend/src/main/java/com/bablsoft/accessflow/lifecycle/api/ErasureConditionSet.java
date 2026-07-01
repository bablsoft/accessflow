package com.bablsoft.accessflow.lifecycle.api;

import java.util.List;

/**
 * A structured erasure predicate: an AND-combined list of {@link ErasureCondition}s. Serialized to
 * the {@code conditions} JSONB column on retention policies and deletion requests as
 * {@code { "conditions": [...] }}. Shared by both the admin erasure-rule config and the user erasure
 * request (one unified shape, AF-519). Each condition compiles to a bound row-security predicate and
 * all are conjoined; OR / more complex logic goes through the raw-WHERE escape hatch instead.
 */
public record ErasureConditionSet(List<ErasureCondition> conditions) {

    public ErasureConditionSet {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /** Convenience: an empty set (no structured conditions). */
    public static ErasureConditionSet empty() {
        return new ErasureConditionSet(List.of());
    }
}
