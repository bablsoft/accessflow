package com.partqam.accessflow.proxy.api;

import java.time.Duration;
import java.util.List;

public record SelectExecutionResult(
        List<ResultColumn> columns,
        List<List<Object>> rows,
        long rowCount,
        boolean truncated,
        Duration duration) implements QueryExecutionResult {

    public SelectExecutionResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
