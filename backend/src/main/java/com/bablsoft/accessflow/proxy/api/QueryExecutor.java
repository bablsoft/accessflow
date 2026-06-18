package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;

public interface QueryExecutor {

    QueryExecutionResult execute(QueryExecutionRequest request);

    /**
     * Produce a non-committing execution plan + best-effort estimated row impact for the request's
     * query (issue AF-445) <em>without</em> mutating data. The request's row-security directives are
     * applied so the plan reflects the governed query; the statement timeout is enforced as for a
     * normal query (there is no row cap — a dry-run returns no rows). For relational datasources a
     * dialect {@code DryRunPlanner} runs the engine's {@code EXPLAIN}; for engine-managed datasources
     * it delegates to {@code QueryEngine.dryRun}. Engines with no plan concept return an
     * {@link QueryDryRunResult} with {@code supported=false}.
     */
    QueryDryRunResult dryRun(QueryExecutionRequest request);

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
