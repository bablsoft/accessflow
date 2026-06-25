package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * Day-bucketed trend series for a single user's queries (AF-498): one list keyed by
 * {@link QueryStatus}, one keyed by {@link RiskLevel}. Both are ordered by date ascending; a
 * (date, key) pair is present only when its count is &gt; 0.
 */
public record MyQueryTrendsRaw(
        List<MyQueryStatusBucket> statusByDay,
        List<MyQueryRiskBucket> riskByDay) {

    public MyQueryTrendsRaw {
        statusByDay = statusByDay == null ? List.of() : List.copyOf(statusByDay);
        riskByDay = riskByDay == null ? List.of() : List.copyOf(riskByDay);
    }
}
