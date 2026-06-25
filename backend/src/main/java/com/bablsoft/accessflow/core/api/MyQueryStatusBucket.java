package com.bablsoft.accessflow.core.api;

import java.time.LocalDate;

/** One (day, status) → count point in a user's query-status trend series (AF-498). */
public record MyQueryStatusBucket(LocalDate date, QueryStatus status, long count) {
}
