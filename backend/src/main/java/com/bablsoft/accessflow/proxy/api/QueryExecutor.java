package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;

public interface QueryExecutor {

    QueryExecutionResult execute(QueryExecutionRequest request);

    /**
     * Read a bounded, governance-applied sample of rows from a single table/collection (issue
     * AF-443). For relational datasources the executor builds a {@code SELECT * FROM <table>} from
     * the (introspection-validated) identifier and runs it through the same row-security rewrite +
     * column-masking path as {@link #execute(QueryExecutionRequest)}; for engine-managed datasources
     * it delegates to the engine's {@code sampleTable}. The configured row cap and statement timeout
     * are enforced exactly as for a normal query.
     */
    SelectExecutionResult sampleTable(SampleTableRequest request);
}
