package com.bablsoft.accessflow.core.api;

/** Count of a user's queries in a given {@link QueryStatus} (AF-498). */
public record MyQueryStatusCount(QueryStatus status, long count) {
}
