package com.bablsoft.accessflow.dashboard.internal.web;

import com.bablsoft.accessflow.core.api.MyQueryTrendsRaw;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.LocalDate;
import java.util.List;

/** API envelope for the self-scoped query trend series (AF-498). */
public record MyQueryTrendsResponse(
        List<StatusBucketResponse> statusByDay,
        List<RiskBucketResponse> riskByDay) {

    public static MyQueryTrendsResponse from(MyQueryTrendsRaw raw) {
        return new MyQueryTrendsResponse(
                raw.statusByDay().stream()
                        .map(b -> new StatusBucketResponse(b.date(), b.status(), b.count())).toList(),
                raw.riskByDay().stream()
                        .map(b -> new RiskBucketResponse(b.date(), b.riskLevel(), b.count())).toList());
    }

    public record StatusBucketResponse(LocalDate date, QueryStatus status, long count) {
    }

    public record RiskBucketResponse(LocalDate date, RiskLevel riskLevel, long count) {
    }
}
