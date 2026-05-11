package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.core.api.DatasourceConnectionDescriptor;
import com.partqam.accessflow.core.api.DatasourceLookupService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.partqam.accessflow.proxy.api.DatasourceUnavailableException;
import com.partqam.accessflow.proxy.api.QueryExecutionFailedException;
import com.partqam.accessflow.proxy.api.QueryExecutionRequest;
import com.partqam.accessflow.proxy.api.QueryExecutionTimeoutException;
import com.partqam.accessflow.proxy.api.SelectExecutionResult;
import com.partqam.accessflow.proxy.api.UpdateExecutionResult;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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
    private final MessageSource messageSource = mock(MessageSource.class);
    private final JdbcResultRowMapper rowMapper = new JdbcResultRowMapper();
    private final SqlExceptionTranslator translator = new SqlExceptionTranslator(messageSource);
    private final ProxyPoolProperties properties = new ProxyPoolProperties(
            null, null, null, null, null,
            new ProxyPoolProperties.Execution(10_000, Duration.ofSeconds(30), 1_000));
    private final AtomicLong nanos = new AtomicLong();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC);

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
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor(2_000)));
        executor = new DefaultQueryExecutor(poolManager, lookupService, properties,
                rowMapper, translator, clock, messageSource);
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

    private DatasourceConnectionDescriptor descriptor(int maxRows) {
        return new DatasourceConnectionDescriptor(datasourceId, UUID.randomUUID(),
                DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 10, maxRows,
                false, null, true);
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
