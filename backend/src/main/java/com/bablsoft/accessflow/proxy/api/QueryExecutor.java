package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;

public interface QueryExecutor {

    QueryExecutionResult execute(QueryExecutionRequest request);
}
