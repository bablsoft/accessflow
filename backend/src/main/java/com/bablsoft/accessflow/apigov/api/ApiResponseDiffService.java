package com.bablsoft.accessflow.apigov.api;

/**
 * Structural JSON / XML diff of two stored, masked API response snapshots of the same connector and
 * operation (#887). Reads the snapshots only — never calls upstream, never re-masks or unmasks.
 */
public interface ApiResponseDiffService {

    /**
     * @throws ApiRequestNotFoundException if either side is not in the organization or not visible
     *         to the caller.
     * @throws ApiResponseDiffNotAvailableException if the request has no snapshot or no previous run
     *         exists.
     * @throws ApiResponseDiffIncompatibleException if an explicit other side is not a comparable
     *         earlier run.
     * @throws ApiResponseDiffUnsupportedException if a body is neither JSON nor XML, or does not parse.
     */
    ApiResponseDiffView diff(ApiResponseDiffCommand command);
}
