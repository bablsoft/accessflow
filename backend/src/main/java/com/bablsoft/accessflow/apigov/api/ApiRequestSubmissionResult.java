package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

public record ApiRequestSubmissionResult(UUID id, QueryStatus status) {
}
