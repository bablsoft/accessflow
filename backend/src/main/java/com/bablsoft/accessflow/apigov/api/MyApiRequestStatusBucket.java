package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.time.LocalDate;

/** One (day, status) → count point in a user's API-request-status trend series (AF-498 dashboard). */
public record MyApiRequestStatusBucket(LocalDate date, QueryStatus status, long count) {
}
