package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.proxy.api.QueryExecutionException;
import com.partqam.accessflow.proxy.api.QueryExecutionFailedException;
import com.partqam.accessflow.proxy.api.QueryExecutionTimeoutException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Set;

@Component
class SqlExceptionTranslator {

    private static final Set<String> TIMEOUT_SQL_STATES = Set.of(
            "57014", // PostgreSQL: query_canceled
            "HY008", // ODBC / MySQL: operation cancelled
            "70100"  // MySQL: connection was killed
    );

    QueryExecutionException translate(SQLException ex, Duration configuredTimeout) {
        if (ex instanceof SQLTimeoutException
                || (ex.getSQLState() != null && TIMEOUT_SQL_STATES.contains(ex.getSQLState()))) {
            return new QueryExecutionTimeoutException(
                    "Query exceeded timeout of " + configuredTimeout, configuredTimeout, ex);
        }
        return new QueryExecutionFailedException(
                ex.getMessage(),
                ex.getSQLState(),
                ex.getErrorCode(),
                ex);
    }
}
