package com.bablsoft.accessflow.apigov.api;

import java.util.List;

/**
 * Day-bucketed trend series for a single user's governed API requests (AF-498 dashboard): one list
 * keyed by query status, one keyed by AI risk level. Both are ordered by date ascending; a
 * (date, key) pair is present only when its count is &gt; 0.
 */
public record MyApiRequestTrendsRaw(
        List<MyApiRequestStatusBucket> statusByDay,
        List<MyApiRequestRiskBucket> riskByDay) {

    public MyApiRequestTrendsRaw {
        statusByDay = statusByDay == null ? List.of() : List.copyOf(statusByDay);
        riskByDay = riskByDay == null ? List.of() : List.copyOf(riskByDay);
    }
}
