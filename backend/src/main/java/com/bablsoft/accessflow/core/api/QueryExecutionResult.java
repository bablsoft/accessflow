package com.bablsoft.accessflow.core.api;

import java.time.Duration;

public sealed interface QueryExecutionResult permits SelectExecutionResult, UpdateExecutionResult {

    Duration duration();
}
