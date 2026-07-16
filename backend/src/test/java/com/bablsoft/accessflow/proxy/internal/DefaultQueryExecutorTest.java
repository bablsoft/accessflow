package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.proxy.internal.dryrun.DryRunPlanner;
import com.bablsoft.accessflow.proxy.internal.dryrun.DryRunPlannerRegistry;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultQueryExecutorTest {

    private final UUID datasourceId = UUID.randomUUID();
    private final DatasourceConnectionPoolManager poolManager = mock(DatasourceConnectionPoolManager.class);
    private final DatasourceLookupService lookupService = mock(DatasourceLookupService.class);
    private final com.bablsoft.accessflow.audit.api.AuditLogService auditLogService =
            mock(com.bablsoft.accessflow.audit.api.AuditLogService.class);
    private final MessageSource messageSource = mock(MessageSource.class);
    private final JdbcResultRowMapper rowMapper = new JdbcResultRowMapper();
    private final SqlExceptionTranslator translator = new SqlExceptionTranslator(messageSource);
    private final ProxyPoolProperties properties = new ProxyPoolProperties(
            null, null, null, null, null,
            new ProxyPoolProperties.Execution(10_000, Duration.ofSeconds(30), 1_000, null));
    private final AtomicLong nanos = new AtomicLong();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC);

    private final com.bablsoft.accessflow.core.api.QueryEngineCatalog engineCatalog =
            mock(com.bablsoft.accessflow.core.api.QueryEngineCatalog.class);
    private final DryRunPlanner dryRunPlanner = mock(DryRunPlanner.class);
    private final SelectResultCache resultCache = mock(SelectResultCache.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();

    private DefaultQueryExecutor executor;
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(poolManager.resolve(datasourceId)).thenReturn(dataSource);
        when(poolManager.replicaEndpoints(datasourceId)).thenReturn(List.of());
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(2_000)));
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        var healthRegistry = new ReplicaHealthRegistry(clock,
                new ProxyReplicaProperties(null, null, null));
        var router = new RoutingDataSourceResolver(poolManager, healthRegistry, lookupService,
                auditLogService, messageSource, observationRegistry);
        when(dryRunPlanner.supportedTypes()).thenReturn(java.util.Set.of(DbType.POSTGRESQL));
        var dryRunRegistry = new DryRunPlannerRegistry(List.of(dryRunPlanner));
        executor = new DefaultQueryExecutor(router, lookupService, properties,
                rowMapper, translator, new RowSecurityRewriter(messageSource),
                engineCatalog, dryRunRegistry, resultCache, clock, messageSource,
                observationRegistry);
    }

    @Test
    void recordsExecuteObservationWithEngineStatementAndOutcomeTags() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);

        executor.execute(new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null));

        assertThat(meterRegistry.get("accessflow.query.execute")
                .tags("db_type", "POSTGRESQL", "query_type", "SELECT", "outcome", "success")
                .timer().count()).isEqualTo(1);
        // datasource.acquire nests as a child span/meter of the execute observation
        assertThat(meterRegistry.get("accessflow.datasource.acquire")
                .tags("query_type", "SELECT", "outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void selectHappyPathReturnsRowsAndAppliesLimits() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);

        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null);

        var result = executor.execute(request);

        assertThat(result).isInstanceOf(SelectExecutionResult.class);
        verify(connection).setReadOnly(true);
        verify(statement).setQueryTimeout(30);
        verify(statement).setMaxRows(2_001);
        verify(statement).setFetchSize(1_000);
    }

    @Test
    void selectTruncatesAtEffectiveMaxRows() throws SQLException {
        var rs = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("v");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(metadata.getColumnTypeName(1)).thenReturn("int4");
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.getInt(1)).thenReturn(1);
        when(rs.getObject(1)).thenReturn(1);
        when(rs.wasNull()).thenReturn(false);
        when(rs.next()).thenReturn(true, true, true, true);
        when(statement.executeQuery()).thenReturn(rs);

        var request = new QueryExecutionRequest(datasourceId, "SELECT v FROM t",
                QueryType.SELECT, 3, null);

        var result = (SelectExecutionResult) executor.execute(request);

        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
        verify(statement).setMaxRows(4);
    }

    @Test
    void selectAppliesColumnMaskDirectiveAndReportsAppliedPolicyId() throws SQLException {
        var rs = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("email");
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(1)).thenReturn("varchar");
        when(metadata.getSchemaName(1)).thenReturn("public");
        when(metadata.getTableName(1)).thenReturn("users");
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.getString(1)).thenReturn("jane@example.com");
        when(rs.wasNull()).thenReturn(false);
        when(rs.next()).thenReturn(true, false);
        when(statement.executeQuery()).thenReturn(rs);

        var policyId = UUID.randomUUID();
        var request = new QueryExecutionRequest(datasourceId, "SELECT email FROM users",
                QueryType.SELECT, null, null, List.of(),
                List.of(new ColumnMaskDirective("public.users.email", MaskingStrategy.EMAIL,
                        Map.of(), policyId)), List.of(), false, null);

        var result = (SelectExecutionResult) executor.execute(request);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("j***@example.com");
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(policyId);
    }

    @Test
    void updateExecutesUpdateAndReturnsAffected() throws SQLException {
        when(statement.executeLargeUpdate()).thenReturn(7L);

        var request = new QueryExecutionRequest(datasourceId, "UPDATE t SET v=1",
                QueryType.UPDATE, null, null);

        var result = (UpdateExecutionResult) executor.execute(request);

        assertThat(result.rowsAffected()).isEqualTo(7L);
        verify(connection).setReadOnly(false);
        verify(statement, never()).setMaxRows(7);
        verify(statement, never()).executeQuery();
        verify(statement, times(1)).executeLargeUpdate();
    }

    @Test
    void ddlReturnsZeroAffected() throws SQLException {
        when(statement.executeLargeUpdate()).thenReturn(0L);

        var request = new QueryExecutionRequest(datasourceId, "CREATE TABLE t(id int)",
                QueryType.DDL, null, null);

        var result = (UpdateExecutionResult) executor.execute(request);

        assertThat(result.rowsAffected()).isEqualTo(0L);
    }

    @Test
    void timeoutBubblesAsTimeoutException() throws SQLException {
        when(statement.executeQuery())
                .thenThrow(new SQLTimeoutException("timed out", "57014", 0));

        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null);

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void genericSqlExceptionBubblesAsFailedException() throws SQLException {
        when(statement.executeQuery())
                .thenThrow(new SQLException("relation does not exist", "42P01", 7));

        var request = new QueryExecutionRequest(datasourceId, "SELECT * FROM ghost",
                QueryType.SELECT, null, null);

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(QueryExecutionFailedException.class)
                .satisfies(ex -> assertThat(((QueryExecutionFailedException) ex).sqlState())
                        .isEqualTo("42P01"));
    }

    @Test
    void missingDatasourceThrowsUnavailable() {
        when(lookupService.findById(datasourceId)).thenReturn(Optional.empty());

        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null);

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(DatasourceUnavailableException.class);
    }

    @Test
    void overrideMaxRowsBeatsDatasourceCap() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);

        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, 50, null);

        executor.execute(request);

        verify(statement).setMaxRows(51);
    }

    @Test
    void globalCapClampsLargeDatasourceCap() throws SQLException {
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(999_999)));
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);

        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null);

        executor.execute(request);

        verify(statement).setMaxRows(10_001);
    }

    @Test
    void overrideTimeoutBeatsConfiguredTimeout() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);

        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, Duration.ofSeconds(7));

        executor.execute(request);

        verify(statement).setQueryTimeout(7);
    }

    @Test
    void transactionalBatchesHomogeneousInsertsAndCommits() throws SQLException {
        var batchStmt = mock(PreparedStatement.class);
        when(connection.prepareStatement("INSERT INTO t (id) VALUES (?)")).thenReturn(batchStmt);
        when(batchStmt.executeLargeBatch()).thenReturn(new long[]{1L, 1L});

        var request = new QueryExecutionRequest(datasourceId,
                "BEGIN; INSERT INTO t(id) VALUES (1); INSERT INTO t(id) VALUES (2); COMMIT;",
                QueryType.INSERT, null, null, List.of(), true,
                List.of("INSERT INTO t(id) VALUES (1)", "INSERT INTO t(id) VALUES (2)"));

        var result = (UpdateExecutionResult) executor.execute(request);

        assertThat(result.rowsAffected()).isEqualTo(2L);
        verify(batchStmt).setObject(1, 1L);
        verify(batchStmt).setObject(1, 2L);
        verify(batchStmt, times(2)).addBatch();
        verify(batchStmt, times(1)).executeLargeBatch();
        verify(batchStmt, never()).executeLargeUpdate();
        verify(connection).setReadOnly(false);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void transactionalHeterogeneousStatementsUsePerStatementPath() throws SQLException {
        var stmt1 = mock(PreparedStatement.class);
        var stmt2 = mock(PreparedStatement.class);
        when(connection.prepareStatement("UPDATE t SET v = 1 WHERE id = 1")).thenReturn(stmt1);
        when(connection.prepareStatement("DELETE FROM u WHERE id = 2")).thenReturn(stmt2);
        when(stmt1.executeLargeUpdate()).thenReturn(1L);
        when(stmt2.executeLargeUpdate()).thenReturn(1L);

        var request = new QueryExecutionRequest(datasourceId,
                "BEGIN; UPDATE t SET v = 1 WHERE id = 1; DELETE FROM u WHERE id = 2; COMMIT;",
                QueryType.UPDATE, null, null, List.of(), true,
                List.of("UPDATE t SET v = 1 WHERE id = 1", "DELETE FROM u WHERE id = 2"));

        var result = (UpdateExecutionResult) executor.execute(request);

        assertThat(result.rowsAffected()).isEqualTo(2L);
        verify(connection).commit();
    }

    @Test
    void transactionalBatchFlushesEveryChunkSizeRows() throws SQLException {
        var chunkedProperties = new ProxyPoolProperties(
                null, null, null, null, null,
                new ProxyPoolProperties.Execution(10_000, Duration.ofSeconds(30), 1_000, 2));
        var healthRegistry = new ReplicaHealthRegistry(clock,
                new ProxyReplicaProperties(null, null, null));
        var router = new RoutingDataSourceResolver(poolManager, healthRegistry, lookupService,
                auditLogService, messageSource, observationRegistry);
        var chunkedExecutor = new DefaultQueryExecutor(router, lookupService, chunkedProperties,
                rowMapper, translator, new RowSecurityRewriter(messageSource),
                engineCatalog, new DryRunPlannerRegistry(List.of(dryRunPlanner)), resultCache,
                clock, messageSource, observationRegistry);
        var batchStmt = mock(PreparedStatement.class);
        when(connection.prepareStatement("INSERT INTO t (id) VALUES (?)")).thenReturn(batchStmt);
        when(batchStmt.executeLargeBatch()).thenReturn(new long[]{1L, 1L}, new long[]{1L});

        var request = new QueryExecutionRequest(datasourceId,
                "BEGIN; INSERT INTO t(id) VALUES (1); INSERT INTO t(id) VALUES (2);"
                        + " INSERT INTO t(id) VALUES (3); COMMIT;",
                QueryType.INSERT, null, null, List.of(), true,
                List.of("INSERT INTO t(id) VALUES (1)", "INSERT INTO t(id) VALUES (2)",
                        "INSERT INTO t(id) VALUES (3)"));

        var result = (UpdateExecutionResult) chunkedExecutor.execute(request);

        assertThat(result.rowsAffected()).isEqualTo(3L);
        verify(batchStmt, times(2)).executeLargeBatch();
    }

    @Test
    void transactionalBatchCountsSuccessNoInfoAsOneRow() throws SQLException {
        var batchStmt = mock(PreparedStatement.class);
        when(connection.prepareStatement("INSERT INTO t (id) VALUES (?)")).thenReturn(batchStmt);
        when(batchStmt.executeLargeBatch())
                .thenReturn(new long[]{java.sql.Statement.SUCCESS_NO_INFO, 1L});

        var request = new QueryExecutionRequest(datasourceId,
                "BEGIN; INSERT INTO t(id) VALUES (1); INSERT INTO t(id) VALUES (2); COMMIT;",
                QueryType.INSERT, null, null, List.of(), true,
                List.of("INSERT INTO t(id) VALUES (1)", "INSERT INTO t(id) VALUES (2)"));

        var result = (UpdateExecutionResult) executor.execute(request);

        assertThat(result.rowsAffected()).isEqualTo(2L);
    }

    @Test
    void transactionalRollsBackOnFailure() throws SQLException {
        var batchStmt = mock(PreparedStatement.class);
        when(connection.prepareStatement("INSERT INTO t (id) VALUES (?)")).thenReturn(batchStmt);
        when(batchStmt.executeLargeBatch())
                .thenThrow(new SQLException("duplicate key", "23505", 7));

        var request = new QueryExecutionRequest(datasourceId,
                "BEGIN; INSERT INTO t(id) VALUES (1); INSERT INTO t(id) VALUES (1); COMMIT;",
                QueryType.INSERT, null, null, List.of(), true,
                List.of("INSERT INTO t(id) VALUES (1)", "INSERT INTO t(id) VALUES (1)"));

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(QueryExecutionFailedException.class);

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void selectCacheHitReturnsCachedResultWithoutTouchingJdbc() throws SQLException {
        var cached = new SelectExecutionResult(List.of(), List.of(), 0, false, Duration.ZERO);
        when(resultCache.enabledFor(any())).thenReturn(true);
        when(resultCache.get(eq(datasourceId), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(cached));

        var request = new QueryExecutionRequest(datasourceId, "SELECT v FROM t",
                QueryType.SELECT, null, null, List.of(), List.of(), List.of(), false, null,
                List.of(), java.util.Set.of("t"));

        var result = executor.execute(request);

        assertThat(result).isSameAs(cached);
        verify(poolManager, never()).resolve(datasourceId);
        verify(resultCache, never()).put(any(), anyString(), any(), any(), any());
    }

    @Test
    void selectCacheMissExecutesAndStoresResult() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);
        when(resultCache.enabledFor(any())).thenReturn(true);
        when(resultCache.get(eq(datasourceId), anyString(), any(Duration.class)))
                .thenReturn(Optional.empty());
        when(resultCache.ttlFor(any())).thenReturn(Duration.ofSeconds(45));

        var request = new QueryExecutionRequest(datasourceId, "SELECT v FROM t",
                QueryType.SELECT, null, null, List.of(), List.of(), List.of(), false, null,
                List.of(), java.util.Set.of("t"));

        var result = executor.execute(request);

        assertThat(result).isInstanceOf(SelectExecutionResult.class);
        verify(resultCache).put(eq(datasourceId), anyString(), eq(java.util.Set.of("t")),
                eq(Duration.ofSeconds(45)), any(SelectExecutionResult.class));
    }

    @Test
    void selectWithUnknownReferencedTablesIsNeverCached() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);
        when(resultCache.enabledFor(any())).thenReturn(true);

        executor.execute(new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null));

        verify(resultCache, never()).get(any(), anyString(), any());
        verify(resultCache, never()).put(any(), anyString(), any(), any(), any());
    }

    @Test
    void selectWithCacheDisabledForDatasourceSkipsCache() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);
        when(resultCache.enabledFor(any())).thenReturn(false);

        executor.execute(new QueryExecutionRequest(datasourceId, "SELECT v FROM t",
                QueryType.SELECT, null, null, List.of(), List.of(), List.of(), false, null,
                List.of(), java.util.Set.of("t")));

        verify(resultCache, never()).get(any(), anyString(), any());
        verify(resultCache, never()).put(any(), anyString(), any(), any(), any());
    }

    @Test
    void updateInvalidatesCachedEntriesForReferencedTables() throws SQLException {
        when(statement.executeLargeUpdate()).thenReturn(3L);

        executor.execute(new QueryExecutionRequest(datasourceId, "UPDATE t SET v = 1",
                QueryType.UPDATE, null, null, List.of(), List.of(), List.of(), false, null,
                List.of(), java.util.Set.of("t")));

        verify(resultCache).invalidateTables(datasourceId, java.util.Set.of("t"));
    }

    @Test
    void transactionalCommitInvalidatesCachedEntriesForReferencedTables() throws SQLException {
        var batchStmt = mock(PreparedStatement.class);
        when(connection.prepareStatement("INSERT INTO t (id) VALUES (?)")).thenReturn(batchStmt);
        when(batchStmt.executeLargeBatch()).thenReturn(new long[]{1L, 1L});

        executor.execute(new QueryExecutionRequest(datasourceId,
                "BEGIN; INSERT INTO t(id) VALUES (1); INSERT INTO t(id) VALUES (2); COMMIT;",
                QueryType.INSERT, null, null, List.of(), List.of(), List.of(), true,
                List.of("INSERT INTO t(id) VALUES (1)", "INSERT INTO t(id) VALUES (2)"),
                List.of(), java.util.Set.of("t")));

        verify(resultCache).invalidateTables(datasourceId, java.util.Set.of("t"));
    }

    @Test
    void selectAppliesRowSecurityPredicateAndBindsParameter() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);
        var policyId = UUID.randomUUID();
        var directive = new RowSecurityDirective(policyId, "t", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
        var request = new QueryExecutionRequest(datasourceId, "SELECT v FROM t", QueryType.SELECT,
                null, null, List.of(), List.of(), List.of(directive), false, null);

        var result = (SelectExecutionResult) executor.execute(request);

        verify(connection).prepareStatement(contains("(SELECT * FROM t WHERE region = ?)"));
        verify(statement).setObject(1, "EU");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policyId);
    }

    @Test
    void updateAppliesRowSecurityPredicateAndBindsParameter() throws SQLException {
        when(statement.executeLargeUpdate()).thenReturn(3L);
        var policyId = UUID.randomUUID();
        var directive = new RowSecurityDirective(policyId, "t", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
        var request = new QueryExecutionRequest(datasourceId, "UPDATE t SET v = 1",
                QueryType.UPDATE, null, null, List.of(), List.of(), List.of(directive), false, null);

        var result = (UpdateExecutionResult) executor.execute(request);

        verify(statement).setObject(eq(1), eq("EU"));
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policyId);
    }

    @Test
    void mongoDatasourceDispatchesToEngineFromCatalogWithEffectiveLimits() {
        var mongoDescriptor = new DatasourceConnectionDescriptor(datasourceId, UUID.randomUUID(),
                DbType.MONGODB, "h", 27017, "db", "u", "ENC", SslMode.DISABLE, 10, 2_000,
                false, null, false, null, null, null, null, null, null, true);
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(mongoDescriptor));
        var engine = mock(com.bablsoft.accessflow.core.api.QueryEngine.class);
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);
        when(engineCatalog.engineFor(DbType.MONGODB)).thenReturn(engine);
        var expected = new UpdateExecutionResult(1, Duration.ZERO, java.util.Set.of());
        when(engine.execute(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        var request = new QueryExecutionRequest(datasourceId, "db.users.find({})",
                QueryType.SELECT, null, null);
        var result = executor.execute(request);

        assertThat(result).isSameAs(expected);
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest.class);
        verify(engine).execute(captor.capture());
        assertThat(captor.getValue().descriptor()).isSameAs(mongoDescriptor);
        assertThat(captor.getValue().effectiveMaxRows()).isEqualTo(2_000);
        assertThat(captor.getValue().effectiveTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void sampleTableRelationalBuildsQuotedSelectAndAppliesLimits() throws SQLException {
        var rs = emptyResultSet();
        when(statement.executeQuery()).thenReturn(rs);
        when(engineCatalog.isEngineManaged(DbType.POSTGRESQL)).thenReturn(false);

        var request = new com.bablsoft.accessflow.core.api.SampleTableRequest(
                datasourceId, "public", "users", 50, null);

        var result = executor.sampleTable(request);

        assertThat(result).isInstanceOf(SelectExecutionResult.class);
        verify(connection).prepareStatement(eq("SELECT * FROM \"public\".\"users\""));
        verify(connection).setReadOnly(true);
        verify(statement).setMaxRows(51);
        verify(statement).setQueryTimeout(30);
    }

    @Test
    void sampleTableEngineManagedDispatchesToEngineWithEffectiveLimits() {
        var mongoDescriptor = new DatasourceConnectionDescriptor(datasourceId, UUID.randomUUID(),
                DbType.MONGODB, "h", 27017, "db", "u", "ENC", SslMode.DISABLE, 10, 2_000,
                false, null, false, null, null, null, null, null, null, true);
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(mongoDescriptor));
        var engine = mock(com.bablsoft.accessflow.core.api.QueryEngine.class);
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);
        when(engineCatalog.engineFor(DbType.MONGODB)).thenReturn(engine);
        var expected = new SelectExecutionResult(List.of(), List.of(), 0, false, Duration.ZERO);
        when(engine.sampleTable(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        var request = new com.bablsoft.accessflow.core.api.SampleTableRequest(
                datasourceId, null, "users", null, null);
        var result = executor.sampleTable(request);

        assertThat(result).isSameAs(expected);
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.core.api.QueryEngineSampleRequest.class);
        verify(engine).sampleTable(captor.capture());
        assertThat(captor.getValue().descriptor()).isSameAs(mongoDescriptor);
        assertThat(captor.getValue().effectiveMaxRows()).isEqualTo(2_000);
        assertThat(captor.getValue().effectiveTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(captor.getValue().request().table()).isEqualTo("users");
    }

    @Test
    void dryRunRelationalDelegatesToDialectPlanner() throws SQLException {
        var planResult = QueryDryRunResult.of("postgresql", QueryType.SELECT, 42L, null, "{}",
                java.util.Set.of(), Duration.ZERO);
        when(dryRunPlanner.plan(org.mockito.ArgumentMatchers.any())).thenReturn(planResult);

        var request = new QueryExecutionRequest(datasourceId, "SELECT 1", QueryType.SELECT,
                null, null);

        var result = executor.dryRun(request);

        assertThat(result.supported()).isTrue();
        assertThat(result.estimatedRows()).isEqualTo(42L);
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.proxy.internal.dryrun.DryRunPlanRequest.class);
        verify(dryRunPlanner).plan(captor.capture());
        assertThat(captor.getValue().engineId()).isEqualTo("postgresql");
        assertThat(captor.getValue().sql()).isEqualTo("SELECT 1");
    }

    @Test
    void dryRunUnsupportedDialectReturnsUnsupported() {
        var oracle = new DatasourceConnectionDescriptor(datasourceId, UUID.randomUUID(),
                DbType.ORACLE, "h", 1521, "db", "u", "ENC", SslMode.DISABLE, 10, 2_000,
                false, null, false, null, null, null, null, null, null, true);
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(oracle));

        var request = new QueryExecutionRequest(datasourceId, "SELECT 1", QueryType.SELECT,
                null, null);

        var result = executor.dryRun(request);

        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("oracle");
    }

    @Test
    void dryRunEngineManagedDelegatesToEngine() {
        var mongoDescriptor = new DatasourceConnectionDescriptor(datasourceId, UUID.randomUUID(),
                DbType.MONGODB, "h", 27017, "db", "u", "ENC", SslMode.DISABLE, 10, 2_000,
                false, null, false, null, null, null, null, null, null, true);
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(mongoDescriptor));
        var engine = mock(com.bablsoft.accessflow.core.api.QueryEngine.class);
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);
        when(engineCatalog.engineFor(DbType.MONGODB)).thenReturn(engine);
        var expected = QueryDryRunResult.unsupported("mongodb", "INSERT has no plan");
        when(engine.dryRun(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        var request = new QueryExecutionRequest(datasourceId, "db.users.insertOne({})",
                QueryType.INSERT, null, null);
        var result = executor.dryRun(request);

        assertThat(result).isSameAs(expected);
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest.class);
        verify(engine).dryRun(captor.capture());
        assertThat(captor.getValue().descriptor()).isSameAs(mongoDescriptor);
        assertThat(captor.getValue().effectiveTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    private DatasourceConnectionDescriptor descriptor(int maxRows) {
        return new DatasourceConnectionDescriptor(datasourceId, UUID.randomUUID(),
                DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 10, maxRows,
                false, null, false, null, null, null, null, null, null, true);
    }

    private static ResultSet emptyResultSet() throws SQLException {
        var rs = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(0);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(false);
        return rs;
    }
}
