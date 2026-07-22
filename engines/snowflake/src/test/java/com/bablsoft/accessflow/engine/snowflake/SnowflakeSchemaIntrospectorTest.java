package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnowflakeSchemaIntrospectorTest {

    private final SnowflakeConnectionFactory connectionFactory =
            mock(SnowflakeConnectionFactory.class);
    private final SnowflakeSchemaIntrospector introspector =
            new SnowflakeSchemaIntrospector(connectionFactory, TestMessages.keyEcho());

    private static DatasourceConnectionDescriptor descriptor(String database) {        // that predate the SNOWFLAKE enum value.
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.SNOWFLAKE, "acct.snowflakecomputing.com", 443, database, "svc", "cipher",
                SslMode.REQUIRE, 1, 1000, false, null, false, null, "snowflake", null,
                null, null, null, true);
    }

    /** A mocked forward-only result set over (columnLabel → value) rows. */
    private static ResultSet rows(List<List<Object>> data, List<String> labels)
            throws SQLException {
        var resultSet = mock(ResultSet.class);
        var cursor = new int[]{-1};
        when(resultSet.next()).thenAnswer(inv -> ++cursor[0] < data.size());
        when(resultSet.getString(any(String.class))).thenAnswer(inv ->
                data.get(cursor[0]).get(labels.indexOf(inv.getArgument(0, String.class))));
        when(resultSet.getInt(any(String.class))).thenAnswer(inv ->
                data.get(cursor[0]).get(labels.indexOf(inv.getArgument(0, String.class))));
        return resultSet;
    }

    @Test
    void groupsTablesBySchemaExcludingInformationSchema() throws SQLException {
        var descriptor = descriptor("ANALYTICS");
        var connection = mock(Connection.class);
        var metaData = mock(DatabaseMetaData.class);
        when(connectionFactory.open(descriptor)).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);

        var tableRows = rows(List.of(
                        List.of("PUBLIC", "ORDERS"),
                        List.of("INFORMATION_SCHEMA", "TABLES"),
                        List.of("STAGING", "RAW_EVENTS")),
                List.of("TABLE_SCHEM", "TABLE_NAME"));
        when(metaData.getTables(eq("ANALYTICS"), eq(null), eq("%"), any()))
                .thenReturn(tableRows);
        when(metaData.getPrimaryKeys(eq("ANALYTICS"), any(), any()))
                .thenAnswer(inv -> "ORDERS".equals(inv.getArgument(2))
                        ? rows(List.of(List.of("ID")), List.of("COLUMN_NAME"))
                        : rows(List.of(), List.of("COLUMN_NAME")));
        when(metaData.getColumns(eq("ANALYTICS"), any(), any(), eq("%")))
                .thenAnswer(inv -> "ORDERS".equals(inv.getArgument(2))
                        ? rows(List.of(
                                        List.of("ID", "NUMBER", DatabaseMetaData.columnNoNulls),
                                        List.of("EMAIL", "VARCHAR", DatabaseMetaData.columnNullable)),
                                List.of("COLUMN_NAME", "TYPE_NAME", "NULLABLE"))
                        : rows(List.of(
                                        List.of("PAYLOAD", "VARIANT", DatabaseMetaData.columnNullable)),
                                List.of("COLUMN_NAME", "TYPE_NAME", "NULLABLE")));

        var view = introspector.introspect(descriptor);

        assertThat(view.schemas()).extracting(DatabaseSchemaView.Schema::name)
                .containsExactly("PUBLIC", "STAGING");
        var orders = view.schemas().get(0).tables().get(0);
        assertThat(orders.name()).isEqualTo("ORDERS");
        assertThat(orders.columns()).containsExactly(
                new DatabaseSchemaView.Column("ID", "NUMBER", false, true),
                new DatabaseSchemaView.Column("EMAIL", "VARCHAR", true, false));
        assertThat(orders.foreignKeys()).isEmpty();
        var rawEvents = view.schemas().get(1).tables().get(0);
        assertThat(rawEvents.columns()).containsExactly(
                new DatabaseSchemaView.Column("PAYLOAD", "VARIANT", true, false));
        verify(connection).close();
    }

    @Test
    void blankDatabaseNameMeansNullCatalog() throws SQLException {
        var descriptor = descriptor(" ");
        var connection = mock(Connection.class);
        var metaData = mock(DatabaseMetaData.class);
        when(connectionFactory.open(descriptor)).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        var noRows = rows(List.of(), List.of("TABLE_SCHEM", "TABLE_NAME"));
        when(metaData.getTables(eq(null), eq(null), eq("%"), any())).thenReturn(noRows);

        var view = introspector.introspect(descriptor);

        assertThat(view.schemas()).isEmpty();
        verify(metaData).getTables(eq(null), eq(null), eq("%"), any());
    }

    @Test
    void sqlFailureBecomesConnectionTestException() throws SQLException {
        var descriptor = descriptor("DB");
        when(connectionFactory.open(descriptor)).thenThrow(new SQLException("no network"));
        assertThatThrownBy(() -> introspector.introspect(descriptor))
                .isInstanceOf(DatasourceConnectionTestException.class)
                .hasMessage("no network");
    }

    @Test
    void configFailureBecomesConnectionTestExceptionWithResolvedMessage() throws SQLException {
        var descriptor = descriptor("DB");
        when(connectionFactory.open(descriptor)).thenThrow(
                new SnowflakeConfigException("error.snowflake.invalid_url_override", "bad"));
        assertThatThrownBy(() -> introspector.introspect(descriptor))
                .isInstanceOf(DatasourceConnectionTestException.class)
                .hasMessageContaining("error.snowflake.invalid_url_override");
    }
}
