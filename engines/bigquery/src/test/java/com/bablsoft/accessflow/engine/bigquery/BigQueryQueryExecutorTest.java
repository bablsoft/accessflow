package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SslMode;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the dry-run path (AF-634): the native BigQuery dry-run job flag, the
 * {@code totalBytesProcessed} mapping, and the AF-445 governance contract (row security spliced
 * into the planned statement, DDL and deny-all short-circuits without touching the client).
 * The execute/sample paths are covered end-to-end by {@link BigQueryQueryEngineIntegrationTest}.
 */
class BigQueryQueryExecutorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final BigQueryClientManager clientManager = mock(BigQueryClientManager.class);
    private final BigQuery client = mock(BigQuery.class);
    private final BigQueryQueryExecutor executor = new BigQueryQueryExecutor(
            clientManager, new BigQueryQueryParser(TestMessages.keyEcho()),
            new BigQueryRowSecurityApplier(TestMessages.keyEcho()), new BigQueryResultMapper(),
            new BigQueryExceptionTranslator(TestMessages.keyEcho()), TestMessages.keyEcho(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    private final DatasourceConnectionDescriptor descriptor = new DatasourceConnectionDescriptor(
            UUID.randomUUID(), UUID.randomUUID(), DbType.BIGQUERY, null, null, "test.dataset1",
            null, "unused-cipher", SslMode.DISABLE, 10, 1000, true, null, false, null, "bigquery",
            null, null, null, null, true, null);

    private com.google.cloud.bigquery.Job stubJob(Long totalBytesProcessed) {
        var job = mock(com.google.cloud.bigquery.Job.class);
        var statistics = mock(JobStatistics.QueryStatistics.class);
        when(statistics.getTotalBytesProcessed()).thenReturn(totalBytesProcessed);
        doReturn(statistics).when(job).getStatistics();
        return job;
    }

    private QueryExecutionRequest request(String sql, QueryType type,
                                          RowSecurityDirective... directives) {
        return new QueryExecutionRequest(descriptor.id(), sql, type, null, null,
                List.of(), List.of(), List.of(directives), false, List.of(sql));
    }

    @Test
    void dryRunSubmitsNativeDryRunJobAndMapsTotalBytesProcessed() {
        var policyId = UUID.randomUUID();
        var job = stubJob(10_500_000L);
        when(clientManager.client(descriptor)).thenReturn(client);
        when(client.create(any(JobInfo.class))).thenReturn(job);

        var result = executor.dryRun(request("SELECT * FROM dataset1.users", QueryType.SELECT,
                new RowSecurityDirective(policyId, "dataset1.users", "tenant",
                        RowSecurityOperator.EQUALS, List.of("acme"))),
                descriptor, TIMEOUT);

        assertThat(result.supported()).isTrue();
        assertThat(result.engineId()).isEqualTo("bigquery");
        assertThat(result.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(result.estimatedBytesScanned()).isEqualTo(10_500_000L);
        assertThat(result.estimatedRows()).isNull();
        assertThat(result.plan()).isNull();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policyId);

        var captor = ArgumentCaptor.forClass(JobInfo.class);
        verify(client).create(captor.capture());
        QueryJobConfiguration configuration = captor.getValue().getConfiguration();
        assertThat(configuration.dryRun()).isTrue();
        assertThat(configuration.getQuery())
                .isEqualTo("SELECT * FROM dataset1.users WHERE (`tenant` = ?)");
        assertThat(configuration.getPositionalParameters()).hasSize(1);
        assertThat(configuration.getPositionalParameters().getFirst().getValue())
                .isEqualTo("acme");
    }

    @Test
    void dryRunReportsNullBytesWhenStatisticsAbsent() {
        when(clientManager.client(descriptor)).thenReturn(client);
        var job = mock(com.google.cloud.bigquery.Job.class);
        doReturn(null).when(job).getStatistics();
        when(client.create(any(JobInfo.class))).thenReturn(job);

        var result = executor.dryRun(request("SELECT 1", QueryType.SELECT), descriptor, TIMEOUT);

        assertThat(result.supported()).isTrue();
        assertThat(result.estimatedBytesScanned()).isNull();
    }

    @Test
    void dryRunClassifiesDmlAndKeepsDryRunFlag() {
        var job = stubJob(2048L);
        when(clientManager.client(descriptor)).thenReturn(client);
        when(client.create(any(JobInfo.class))).thenReturn(job);

        var result = executor.dryRun(
                request("DELETE FROM dataset1.users WHERE id = 1", QueryType.DELETE),
                descriptor, TIMEOUT);

        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.DELETE);
        assertThat(result.estimatedBytesScanned()).isEqualTo(2048L);
        var captor = ArgumentCaptor.forClass(JobInfo.class);
        verify(client).create(captor.capture());
        assertThat(((QueryJobConfiguration) captor.getValue().getConfiguration()).dryRun())
                .isTrue();
    }

    @Test
    void dryRunDdlIsUnsupportedWithoutTouchingBigQuery() {
        var result = executor.dryRun(
                request("CREATE TABLE dataset1.x (id INT64)", QueryType.DDL), descriptor, TIMEOUT);

        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("bigquery");
        verifyNoInteractions(clientManager);
    }

    @Test
    void dryRunDenyAllShortCircuitsWithoutTouchingBigQuery() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "dataset1.users", "tenant",
                RowSecurityOperator.IN, List.of());

        var result = executor.dryRun(
                request("SELECT * FROM dataset1.users", QueryType.SELECT, directive),
                descriptor, TIMEOUT);

        assertThat(result.supported()).isTrue();
        assertThat(result.estimatedRows()).isZero();
        assertThat(result.estimatedBytesScanned()).isZero();
        assertThat(result.plan()).isNull();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(directive.policyId());
        verifyNoInteractions(clientManager);
    }

    @Test
    void dryRunTranslatesBigQueryException() {
        when(clientManager.client(descriptor)).thenReturn(client);
        when(client.create(any(JobInfo.class)))
                .thenThrow(new BigQueryException(400, "invalid query"));

        assertThatThrownBy(() -> executor.dryRun(request("SELECT 1", QueryType.SELECT),
                descriptor, TIMEOUT))
                .isInstanceOf(QueryExecutionException.class);
    }
}
