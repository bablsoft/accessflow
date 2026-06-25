package com.bablsoft.accessflow.dashboard.api;

import com.bablsoft.accessflow.core.api.RiskLevel;

/** Count of a user's queries at a given {@link RiskLevel} within a reporting window (AF-498). */
public record DashboardRiskCount(RiskLevel riskLevel, long count) {
}
