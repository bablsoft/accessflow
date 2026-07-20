package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableSummaryView;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;

/**
 * The submitter-visible projection of a connector variable (AF-613) — what the request composer
 * needs to reference {@code {{name}}} and offer an override. Deliberately omits the expression,
 * algorithm, encoding and any hint of a stored secret.
 */
public record ApiConnectorVariableSummaryResponse(
        String name,
        ApiVariableKind kind,
        String description,
        boolean overridable) {

    public static ApiConnectorVariableSummaryResponse from(ApiConnectorVariableSummaryView v) {
        return new ApiConnectorVariableSummaryResponse(v.name(), v.kind(), v.description(),
                v.overridable());
    }
}
