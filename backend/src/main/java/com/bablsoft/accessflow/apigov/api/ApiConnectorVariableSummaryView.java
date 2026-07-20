package com.bablsoft.accessflow.apigov.api;

/**
 * The submitter-visible projection of a connector variable (AF-613): enough to compose a request
 * that references {@code {{name}}} and to offer an override, and nothing more.
 *
 * <p>Deliberately omits {@code expression}, {@code algorithm}, {@code encoding} and any hint of a
 * stored secret — a submitter may reference a signature variable but must never learn how it is
 * computed or what key backs it.
 */
public record ApiConnectorVariableSummaryView(
        String name,
        ApiVariableKind kind,
        String description,
        boolean overridable) {
}
