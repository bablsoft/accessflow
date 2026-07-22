package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SnowflakeQueryExecutorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final SnowflakeConnectionFactory connectionFactory =
            mock(SnowflakeConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement prepared = mock(PreparedStatement.class);
    private final DatasourceConnectionDescriptor descriptor = descriptor();

    private final SnowflakeQueryExecutor executor = new SnowflakeQueryExecutor(
            connectionFactory,
            new SnowflakeQueryParser(TestMessages.keyEcho()),
            new SnowflakeRowSecurityApplier(TestMessages.keyEcho()),
            new SnowflakeResultMapper(),
            new SnowflakeExceptionTranslator(TestMessages.keyEcho()),
            TestMessages.keyEcho(),
            CLOCK);

    private static DatasourceConnectionDescriptor descriptor() {        // that predate the SNOWFLAKE enum value.
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.SNOWFLAKE, "acct.snowflakecomputing.com", 443, "DB", "svc", "cipher",
                SslMode.REQUIRE, 1, 1000, false, null, false, null, "snowflake", null,
                null, null, null, true);
    }

    private static QueryExecutionRequest request(String sql, QueryType type,
                                                 List<RowSecurityDirective> rls,
                                                 List<String> restricted,
                                                 List<ColumnMaskDirective> masks) {
        return new QueryExecutionRequest(UUID.randomUUID(), sql, type, null, null,
                restricted, masks, rls, false, null);
    }

    @BeforeEach
    void wireConnection() throws SQLException {
        when(connectionFactory.open(descriptor)).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
    }

    private ResultSet selectResult(List<List<Object>> rows) throws SQLException {
        var metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getColumnLabel(1)).thenReturn("ID");
        when(metaData.getColumnLabel(2)).thenReturn("EMAIL");
        when(metaData.getColumnType(1)).thenReturn(Types.NUMERIC);
        when(metaData.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(metaData.getColumnTypeName(1)).thenReturn("NUMBER");
        when(metaData.getColumnTypeName(2)).thenReturn("VARCHAR");
        var resultSet = mock(ResultSet.class);
        when(resultSet.getMetaData()).thenReturn(metaData);
        var cursor = new int[]{-1};
        when(resultSet.next()).thenAnswer(inv -> ++cursor[0] < rows.size());
        when(resultSet.getObject(1)).thenAnswer(inv -> rows.get(cursor[0]).get(0));
        when(resultSet.getObject(2)).thenAnswer(inv -> rows.get(cursor[0]).get(1));
        return resultSet;
    }

    @Test
    void executesSelectWithRowSecurityBindsAndMasks() throws SQLException {
        var fixture = selectResult(List.of(List.of(1, "alice@example.com")));
        when(prepared.executeQuery()).thenReturn(fixture);
        var policyId = UUID.randomUUID();
        var rls = List.of(new RowSecurityDirective(policyId, "orders", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme")));
        var masks = List.of(new ColumnMaskDirective("email", MaskingStrategy.EMAIL, null,
                UUID.randomUUID()));

        var result = (SelectExecutionResult) executor.execute(
                request("SELECT * FROM orders", QueryType.SELECT, rls, List.of(), masks),
                descriptor, 100, TIMEOUT);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM orders WHERE (\"tenant\" = ?)");
        verify(prepared).setObject(1, "acme");
        verify(prepared).setQueryTimeout(30);
        verify(prepared).setMaxRows(101);
        assertThat(result.rows().get(0).get(1)).isEqualTo("a***@example.com");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policyId);
        verify(connection).close();
        verify(prepared).close();
    }

    @Test
    void selectWithoutPoliciesKeepsResultUnwrapped() throws SQLException {
        var fixture = selectResult(List.of(List.of(1, "x")));
        when(prepared.executeQuery()).thenReturn(fixture);
        var result = (SelectExecutionResult) executor.execute(
                request("SELECT * FROM orders", QueryType.SELECT, List.of(), List.of(), List.of()),
                descriptor, 5, TIMEOUT);
        assertThat(result.appliedRowSecurityPolicyIds()).isEmpty();
        assertThat(result.rowCount()).isEqualTo(1);
    }

    @Test
    void denyAllSelectShortCircuitsWithoutTouchingSnowflake() throws SQLException {
        var policyId = UUID.randomUUID();
        var rls = List.of(new RowSecurityDirective(policyId, "orders", "tenant",
                RowSecurityOperator.EQUALS, List.of()));
        var result = (SelectExecutionResult) executor.execute(
                request("SELECT * FROM orders", QueryType.SELECT, rls, List.of(), List.of()),
                descriptor, 100, TIMEOUT);
        assertThat(result.rows()).isEmpty();
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policyId);
        verifyNoInteractions(connectionFactory);
    }

    @Test
    void denyAllWriteShortCircuitsToZeroRows() throws SQLException {
        var rls = List.of(new RowSecurityDirective(UUID.randomUUID(), "orders", "tenant",
                RowSecurityOperator.EQUALS, List.of()));
        var result = (UpdateExecutionResult) executor.execute(
                request("DELETE FROM orders", QueryType.DELETE, rls, List.of(), List.of()),
                descriptor, 100, TIMEOUT);
        assertThat(result.rowsAffected()).isZero();
        verifyNoInteractions(connectionFactory);
    }

    @Test
    void executesUpdateReturningAffectedRows() throws SQLException {
        when(prepared.executeUpdate()).thenReturn(7);
        var result = (UpdateExecutionResult) executor.execute(
                request("UPDATE orders SET a = 1", QueryType.UPDATE, List.of(), List.of(),
                        List.of()),
                descriptor, 100, TIMEOUT);
        assertThat(result.rowsAffected()).isEqualTo(7);
        verify(prepared, never()).setMaxRows(101);
    }

    @Test
    void executesDdlReturningZeroRows() throws SQLException {
        var result = (UpdateExecutionResult) executor.execute(
                request("CREATE TABLE t (id INT)", QueryType.DDL, List.of(), List.of(), List.of()),
                descriptor, 100, TIMEOUT);
        assertThat(result.rowsAffected()).isZero();
        verify(prepared).execute();
        verify(prepared, never()).executeUpdate();
    }

    @Test
    void clampsSubSecondTimeoutToOneSecond() throws SQLException {
        var fixture = selectResult(List.of());
        when(prepared.executeQuery()).thenReturn(fixture);
        executor.execute(
                request("SELECT * FROM orders", QueryType.SELECT, List.of(), List.of(), List.of()),
                descriptor, 10, Duration.ofMillis(300));
        verify(prepared).setQueryTimeout(1);
    }

    @Test
    void translatesSqlExceptions() throws SQLException {
        when(prepared.executeQuery()).thenThrow(new SQLTimeoutException("slow"));
        assertThatThrownBy(() -> executor.execute(
                request("SELECT * FROM orders", QueryType.SELECT, List.of(), List.of(), List.of()),
                descriptor, 10, TIMEOUT))
                .isInstanceOf(QueryExecutionTimeoutException.class);

        when(prepared.executeUpdate()).thenThrow(new SQLException("boom", "42000", 999));
        assertThatThrownBy(() -> executor.execute(
                request("DELETE FROM orders", QueryType.DELETE, List.of(), List.of(), List.of()),
                descriptor, 10, TIMEOUT))
                .isInstanceOf(QueryExecutionFailedException.class);
    }

    @Test
    void configExceptionsBecomeExecutionFailures() throws SQLException {
        when(connectionFactory.open(descriptor)).thenThrow(
                new SnowflakeConfigException("error.snowflake.invalid_url_override", "bad"));
        assertThatThrownBy(() -> executor.execute(
                request("SELECT * FROM orders", QueryType.SELECT, List.of(), List.of(), List.of()),
                descriptor, 10, TIMEOUT))
                .isInstanceOf(QueryExecutionFailedException.class)
                .hasMessageContaining("error.snowflake.invalid_url_override");
    }

    @Test
    void sampleTableRunsGovernedQuotedSelect() throws SQLException {
        var fixture = selectResult(List.of(List.of(1, "x")));
        when(prepared.executeQuery()).thenReturn(fixture);
        var sample = new SampleTableRequest(UUID.randomUUID(), "PUBLIC", "Or\"ders",
                List.of(), List.of(), List.of(), null, null);
        var result = executor.sampleTable(sample, descriptor, 50, TIMEOUT);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM \"PUBLIC\".\"Or\"\"ders\"");
        verify(prepared).setMaxRows(51);
        assertThat(result.rowCount()).isEqualTo(1);
    }

    @Test
    void sampleTableWithoutSchemaQuotesBareTable() throws SQLException {
        var fixture = selectResult(List.of());
        when(prepared.executeQuery()).thenReturn(fixture);
        var sample = new SampleTableRequest(UUID.randomUUID(), null, "orders",
                List.of(), List.of(), List.of(), null, null);
        executor.sampleTable(sample, descriptor, 50, TIMEOUT);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM \"orders\"");
    }

    @Test
    void sampleTableAppliesRowSecurity() throws SQLException {
        var fixture = selectResult(List.of());
        when(prepared.executeQuery()).thenReturn(fixture);
        var policyId = UUID.randomUUID();
        var sample = new SampleTableRequest(UUID.randomUUID(), null, "orders",
                List.of(),
                List.of(),
                List.of(new RowSecurityDirective(policyId, "orders", "tenant",
                        RowSecurityOperator.EQUALS, List.of("acme"))),
                null, null);
        var result = executor.sampleTable(sample, descriptor, 50, TIMEOUT);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM \"orders\" WHERE (\"tenant\" = ?)");
        verify(prepared).setObject(1, "acme");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(policyId);
    }
}
