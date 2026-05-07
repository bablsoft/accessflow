package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.proxy.api.QueryExecutionException;
import com.partqam.accessflow.proxy.api.QueryExecutionFailedException;
import com.partqam.accessflow.proxy.api.QueryExecutionTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
class SqlExceptionTranslator {

    private static final Set<String> TIMEOUT_SQL_STATES = Set.of(
            "57014", // PostgreSQL: query_canceled
            "HY008", // ODBC / MySQL: operation cancelled
            "70100"  // MySQL: connection was killed
    );

    private final MessageSource messageSource;

    QueryExecutionException translate(SQLException ex, Duration configuredTimeout, Locale locale) {
        if (ex instanceof SQLTimeoutException
                || (ex.getSQLState() != null && TIMEOUT_SQL_STATES.contains(ex.getSQLState()))) {
            return new QueryExecutionTimeoutException(
                    messageSource.getMessage("error.query_execution_timeout",
                            new Object[]{configuredTimeout.toSeconds()}, locale),
                    configuredTimeout, ex);
        }
        return new QueryExecutionFailedException(
                messageSource.getMessage("error.query_execution_failed", null, locale),
                ex.getSQLState(),
                ex.getErrorCode(),
                ex);
    }
}
