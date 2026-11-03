package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/** The explicit other side is not an earlier executed run of the same connector + operation (#887). */
public class ApiResponseDiffIncompatibleException extends ApiGovException {

    public ApiResponseDiffIncompatibleException(UUID apiRequestId, UUID againstApiRequestId) {
        super("API request " + againstApiRequestId + " cannot be diffed against " + apiRequestId);
    }
}
