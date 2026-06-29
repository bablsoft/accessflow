package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.LocalDate;

/** One (day, risk-level) → count point in a user's API-request-risk trend series (AF-498 dashboard). */
public record MyApiRequestRiskBucket(LocalDate date, RiskLevel riskLevel, long count) {
}
