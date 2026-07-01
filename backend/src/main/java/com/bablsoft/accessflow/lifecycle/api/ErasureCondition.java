package com.bablsoft.accessflow.lifecycle.api;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;

import java.util.List;
import java.util.Objects;

/**
 * A single structured predicate in an {@link ErasureConditionSet}: {@code <column> <operator>
 * <value(s)>}, optionally negated. Mirrors a row-security leaf so the lifecycle predicate compiler
 * can turn each condition into a parameter-bound {@link RowSecurityDirective} — the {@code values}
 * are always bound, never concatenated. This is a persisted contract (serialized to the
 * {@code conditions} JSONB column on both retention policies and deletion requests), so it lives in
 * {@code api} and depends only on JDK types plus {@link RowSecurityOperator}.
 *
 * <p>Value arity follows the operator: {@code IS_NULL} takes no values; {@code IN}/{@code NOT_IN}
 * take one or more; the scalar operators take exactly one.
 */
public record ErasureCondition(
        String column,
        RowSecurityOperator operator,
        List<String> values,
        boolean negate) {

    public ErasureCondition {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        values = values == null ? List.of() : List.copyOf(values);
    }
}
