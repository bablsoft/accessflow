package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AnomalyBadgeView;

/** Compact anomaly indicator for the current user (optionally a single datasource). */
public record AnomalyBadgeResponse(int openCount, double maxScore) {

    public static AnomalyBadgeResponse from(AnomalyBadgeView view) {
        return new AnomalyBadgeResponse(view.openCount(), view.maxScore());
    }
}
