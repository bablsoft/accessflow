package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

/** Count of a user's governed API requests in a given {@link QueryStatus} (AF-498 dashboard). */
public record MyApiRequestStatusCount(QueryStatus status, long count) {
}
