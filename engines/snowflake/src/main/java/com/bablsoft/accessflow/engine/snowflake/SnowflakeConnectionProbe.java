package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.EngineMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Connection test for a Snowflake datasource: opens a short-lived connection and issues
 * {@code SELECT 1} — the same probe as the host's JDBC path (proving the account host, database,
 * and credentials round-trip; the warehouse resumes if suspended).
 */
class SnowflakeConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeConnectionProbe.class);

    private final SnowflakeConnectionFactory connectionFactory;
    private final EngineMessages messages;

    SnowflakeConnectionProbe(SnowflakeConnectionFactory connectionFactory, EngineMessages messages) {
        this.connectionFactory = connectionFactory;
        this.messages = messages;
    }

    ConnectionTestResult test(DatasourceConnectionDescriptor descriptor) {
        var start = System.currentTimeMillis();
        try (var connection = connectionFactory.open(descriptor);
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
            return new ConnectionTestResult(true, System.currentTimeMillis() - start, "ok");
        } catch (SQLException e) {
            log.warn("Snowflake connection test failed for datasource {}: {}",
                    descriptor.id(), e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        } catch (SnowflakeConfigException e) {
            log.warn("Snowflake connection test failed for datasource {}: {}",
                    descriptor.id(), e.messageKey());
            throw new DatasourceConnectionTestException(messages.get(e.messageKey(), e.args()));
        }
    }
}
