package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes a GoogleSQL statement for a {@code BIGQUERY} datasource — the warehouse analogue of the
 * host's JDBC execution path. Flow: re-parse, apply row-security predicates (rewritten SQL +
 * positional {@code ?} parameters, never concatenated), then run the statement as a BigQuery query
 * job pinned to the datasource's default dataset (when {@code database_name} carries one). The
 * host-computed statement timeout is enforced twice: server-side via {@code jobTimeoutMs} and
 * client-side by polling the job against the engine clock — on expiry the job is cancelled and a
 * timeout exception raised. SELECTs page at {@code maxRows + 1} to detect truncation, then map
 * through {@link BigQueryResultMapper} (which applies column masking). A deny-all row-security
 * result short-circuits without touching BigQuery. DML (incl. MERGE) reports the job's
 * {@code numDmlAffectedRows}; DDL returns 0 affected rows.
 */
class BigQueryQueryExecutor {

    private static final long POLL_INTERVAL_MS = 100;

    private final BigQueryClientManager clientManager;
    private final BigQueryQueryParser parser;
    private final BigQueryRowSecurityApplier rowSecurityApplier;
    private final BigQueryResultMapper resultMapper;
    private final BigQueryExceptionTranslator exceptionTranslator;
    private final EngineMessages messages;
    private final Clock clock;

    BigQueryQueryExecutor(BigQueryClientManager clientManager, BigQueryQueryParser parser,
                          BigQueryRowSecurityApplier rowSecurityApplier,
                          BigQueryResultMapper resultMapper,
                          BigQueryExceptionTranslator exceptionTranslator,
                          EngineMessages messages, Clock clock) {
        this.clientManager = clientManager;
        this.parser = parser;
        this.rowSecurityApplier = rowSecurityApplier;
        this.resultMapper = resultMapper;
        this.exceptionTranslator = exceptionTranslator;
        this.messages = messages;
        this.clock = clock;
    }

    QueryExecutionResult execute(QueryExecutionRequest request,
                                 DatasourceConnectionDescriptor descriptor, int maxRows,
                                 Duration timeout) {
        var start = clock.instant();
        var statement = parser.parseStatement(request.sql());
        var applied = rowSecurityApplier.apply(statement, request.rowSecurityPredicates());
        if (applied.denyAll()) {
            return statement.kind().isRead()
                    ? new SelectExecutionResult(List.of(), List.of(), 0, false,
                            durationSince(start)).withRowSecurityPolicyIds(applied.appliedPolicyIds())
                    : new UpdateExecutionResult(0, durationSince(start), applied.appliedPolicyIds());
        }
        try {
            var client = clientManager.client(descriptor);
            var job = client.create(JobInfo.of(configuration(applied, descriptor, timeout)));
            var completed = awaitCompletion(job, timeout);
            var error = completed.getStatus() == null ? null : completed.getStatus().getError();
            if (error != null) {
                throw exceptionTranslator.translateJobError(error, timeout);
            }
            if (statement.kind().isRead()) {
                var result = materializeSelect(completed, maxRows, start, timeout,
                        request.restrictedColumns(), request.columnMasks());
                return applied.appliedPolicyIds().isEmpty()
                        ? result
                        : result.withRowSecurityPolicyIds(applied.appliedPolicyIds());
            }
            if (statement.kind() == BigQueryStatementKind.DDL) {
                return new UpdateExecutionResult(0, durationSince(start));
            }
            return new UpdateExecutionResult(dmlAffectedRows(completed), durationSince(start),
                    applied.appliedPolicyIds());
        } catch (BigQueryException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        } catch (IllegalArgumentException ex) {
            // Client construction failed (invalid service-account key JSON).
            throw new com.bablsoft.accessflow.core.api.QueryExecutionFailedException(
                    messages.get("error.query_execution_failed"), ex.getMessage(), null, 0, ex);
        }
    }

    SelectExecutionResult sampleTable(SampleTableRequest request,
                                      DatasourceConnectionDescriptor descriptor, int maxRows,
                                      Duration timeout) {
        // Sample = SELECT * FROM `project.dataset.table`, funneled through the same parse +
        // row-security + masking pipeline as execute(). The table name comes from introspection
        // (allow-listed); backticks are stripped defensively.
        var sql = "SELECT * FROM `" + qualifiedTable(request, descriptor) + "`";
        var execRequest = new QueryExecutionRequest(request.datasourceId(), sql, QueryType.SELECT,
                null, null, request.restrictedColumns(), request.columnMasks(),
                request.rowSecurityPredicates(), false, null);
        return (SelectExecutionResult) execute(execRequest, descriptor, maxRows, timeout);
    }

    private static String qualifiedTable(SampleTableRequest request,
                                         DatasourceConnectionDescriptor descriptor) {
        var target = BigQueryClientFactory.ProjectTarget.parse(descriptor.databaseName());
        var dataset = request.schema() != null && !request.schema().isBlank()
                ? request.schema().strip()
                : target.defaultDataset();
        var table = request.table().replace("`", "");
        return dataset == null || dataset.isBlank()
                ? table
                : target.projectId() + "." + dataset.replace("`", "") + "." + table;
    }

    // ---- job configuration ----------------------------------------------------------------------

    private static QueryJobConfiguration configuration(BigQueryRowSecurityApplier.Applied applied,
                                                       DatasourceConnectionDescriptor descriptor,
                                                       Duration timeout) {
        var target = BigQueryClientFactory.ProjectTarget.parse(descriptor.databaseName());
        var builder = QueryJobConfiguration.newBuilder(applied.statement())
                .setUseLegacySql(false)
                .setJobTimeoutMs(timeout.toMillis());
        if (target.defaultDataset() != null && !target.defaultDataset().isBlank()) {
            builder.setDefaultDataset(DatasetId.of(target.projectId(), target.defaultDataset()));
        }
        for (var value : applied.parameters()) {
            builder.addPositionalParameter(toParameterValue(value));
        }
        return builder.build();
    }

    private static QueryParameterValue toParameterValue(Object value) {
        return switch (value) {
            case Boolean b -> QueryParameterValue.bool(b);
            case Integer i -> QueryParameterValue.int64(i);
            case Long l -> QueryParameterValue.int64(l);
            case Short s -> QueryParameterValue.int64((long) s);
            case Byte b -> QueryParameterValue.int64((long) b);
            case Double d -> QueryParameterValue.float64(d);
            case Float f -> QueryParameterValue.float64(f);
            case BigDecimal d -> QueryParameterValue.numeric(d);
            case Number n -> QueryParameterValue.numeric(new BigDecimal(n.toString()));
            default -> QueryParameterValue.string(String.valueOf(value));
        };
    }

    // ---- job lifecycle --------------------------------------------------------------------------

    /**
     * Polls the job until DONE, enforcing the host-computed timeout against the engine clock —
     * interruptible (virtual-thread friendly) unlike the client's blocking {@code waitFor}. On
     * expiry the job is cancelled best-effort and a timeout exception raised.
     */
    private Job awaitCompletion(Job job, Duration timeout) {
        var deadline = clock.instant().plus(timeout);
        try {
            while (!job.isDone()) {
                if (!clock.instant().isBefore(deadline)) {
                    cancelQuietly(job);
                    throw exceptionTranslator.timeoutException(timeout, null);
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cancelQuietly(job);
            throw new com.bablsoft.accessflow.core.api.QueryExecutionFailedException(
                    messages.get("error.query_execution_failed"), ex.getMessage(), null, 0, ex);
        }
        var completed = job.reload();
        if (completed == null) {
            throw new com.bablsoft.accessflow.core.api.QueryExecutionFailedException(
                    messages.get("error.query_execution_failed"),
                    "BigQuery job no longer exists", null, 0, null);
        }
        return completed;
    }

    private static void cancelQuietly(Job job) {
        try {
            job.cancel();
        } catch (BigQueryException ignored) {
            // Best-effort cancel; the timeout is reported regardless.
        }
    }

    // ---- result mapping -------------------------------------------------------------------------

    private SelectExecutionResult materializeSelect(Job completed, int maxRows, Instant start,
                                                    Duration timeout,
                                                    List<String> restrictedColumns,
                                                    List<com.bablsoft.accessflow.core.api.ColumnMaskDirective> columnMasks) {
        try {
            var tableResult = completed.getQueryResults(
                    BigQuery.QueryResultsOption.pageSize(maxRows + 1L));
            var rows = new ArrayList<FieldValueList>();
            for (var row : tableResult.iterateAll()) {
                rows.add(row);
                if (rows.size() > maxRows) {
                    break; // maxRows + 1 collected — enough to flag truncation
                }
            }
            return resultMapper.materialize(tableResult.getSchema().getFields(), rows, maxRows,
                    durationSince(start), restrictedColumns, columnMasks);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new com.bablsoft.accessflow.core.api.QueryExecutionFailedException(
                    messages.get("error.query_execution_failed"), ex.getMessage(), null, 0, ex);
        }
    }

    private static long dmlAffectedRows(Job completed) {
        JobStatistics.QueryStatistics statistics = completed.getStatistics();
        var affected = statistics == null ? null : statistics.getNumDmlAffectedRows();
        return affected == null ? 0 : affected;
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
