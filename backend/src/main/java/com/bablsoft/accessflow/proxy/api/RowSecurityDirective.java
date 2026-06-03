package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A resolved row-security predicate passed into query execution: the proxy injects
 * {@code <tableRef>.<columnName> <operator> <value(s)>} as a parameter-bound predicate for every
 * occurrence of {@code tableRef} in the parsed statement (a security-barrier subquery for SELECT, a
 * {@code WHERE} conjunct for UPDATE/DELETE). The {@code values} are bound as JDBC parameters — never
 * string-concatenated. An empty {@code values} list is the fail-closed signal: the proxy emits an
 * always-false predicate so the submitter sees nothing. {@code policyId} is recorded for audit.
 */
public record RowSecurityDirective(
        UUID policyId,
        String tableRef,
        String columnName,
        RowSecurityOperator operator,
        List<Object> values) {

    public RowSecurityDirective {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(tableRef, "tableRef");
        Objects.requireNonNull(columnName, "columnName");
        Objects.requireNonNull(operator, "operator");
        values = values == null ? List.of() : List.copyOf(values);
    }
}
