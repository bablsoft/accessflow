package com.bablsoft.accessflow.proxy.api;

import java.time.Duration;

public sealed interface QueryExecutionResult permits SelectExecutionResult, UpdateExecutionResult {

    Duration duration();
}
