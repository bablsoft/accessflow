package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiRequestView;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** The new request's detail envelope, flattened, plus how many stored overrides the replay dropped (#886). */
public record ApiRequestReplayResponse(
        @JsonUnwrapped ApiRequestResponse request,
        int droppedVariableOverrideCount) {

    static ApiRequestReplayResponse from(ApiRequestView view, int droppedVariableOverrideCount) {
        return new ApiRequestReplayResponse(ApiRequestResponse.from(view), droppedVariableOverrideCount);
    }
}
