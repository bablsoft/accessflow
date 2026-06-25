package com.bablsoft.accessflow.core.api;

import java.time.LocalDate;

/** One (day, risk-level) → count point in a user's query-risk trend series (AF-498). */
public record MyQueryRiskBucket(LocalDate date, RiskLevel riskLevel, long count) {
}
