package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/** The request has no stored snapshot, or there is no previous run to diff against (#887). */
public class ApiResponseDiffNotAvailableException extends ApiGovException {

    private final UUID apiRequestId;

    public ApiResponseDiffNotAvailableException(UUID apiRequestId) {
        super("No response diff available for API request: " + apiRequestId);
        this.apiRequestId = apiRequestId;
    }

    public UUID apiRequestId() {
        return apiRequestId;
    }
}
