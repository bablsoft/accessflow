package com.bablsoft.accessflow.proxy.api;

import java.time.Duration;

public record UpdateExecutionResult(long rowsAffected, Duration duration)
        implements QueryExecutionResult {
}
