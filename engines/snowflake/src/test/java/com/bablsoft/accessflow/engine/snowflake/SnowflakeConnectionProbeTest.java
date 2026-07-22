package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnowflakeConnectionProbeTest {

    private final SnowflakeConnectionFactory connectionFactory =
            mock(SnowflakeConnectionFactory.class);
    private final SnowflakeConnectionProbe probe =
            new SnowflakeConnectionProbe(connectionFactory, TestMessages.keyEcho());

    private static DatasourceConnectionDescriptor descriptor() {        // that predate the SNOWFLAKE enum value.
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.SNOWFLAKE, "acct.snowflakecomputing.com", 443, "DB", "svc", "cipher",
                SslMode.REQUIRE, 1, 1000, false, null, false, null, "snowflake", null,
                null, null, null, true);
    }

    @Test
    void probesWithSelectOneAndReportsLatency() throws SQLException {
        var descriptor = descriptor();
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var resultSet = mock(ResultSet.class);
        when(connectionFactory.open(descriptor)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        var result = probe.test(descriptor);

        assertThat(result.ok()).isTrue();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.message()).isEqualTo("ok");
        verify(connection).close();
    }

    @Test
    void sqlFailureBecomesConnectionTestExceptionWithDriverMessage() throws SQLException {
        var descriptor = descriptor();
        when(connectionFactory.open(descriptor))
                .thenThrow(new SQLException("Incorrect username or password was specified."));
        assertThatThrownBy(() -> probe.test(descriptor))
                .isInstanceOf(DatasourceConnectionTestException.class)
                .hasMessage("Incorrect username or password was specified.");
    }

    @Test
    void configFailureBecomesConnectionTestExceptionWithResolvedMessage() throws SQLException {
        var descriptor = descriptor();
        when(connectionFactory.open(descriptor)).thenThrow(
                new SnowflakeConfigException("error.snowflake.encrypted_private_key_unsupported"));
        assertThatThrownBy(() -> probe.test(descriptor))
                .isInstanceOf(DatasourceConnectionTestException.class)
                .hasMessage("error.snowflake.encrypted_private_key_unsupported");
    }
}
