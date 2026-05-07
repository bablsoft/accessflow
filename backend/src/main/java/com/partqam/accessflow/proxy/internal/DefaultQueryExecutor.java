package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.core.api.DatasourceLookupService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.partqam.accessflow.proxy.api.DatasourceUnavailableException;
import com.partqam.accessflow.proxy.api.QueryExecutionRequest;
import com.partqam.accessflow.proxy.api.QueryExecutionResult;
import com.partqam.accessflow.proxy.api.QueryExecutor;
import com.partqam.accessflow.proxy.api.UpdateExecutionResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryExecutor implements QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryExecutor.class);

    private final DatasourceConnectionPoolManager poolManager;
    private final DatasourceLookupService datasourceLookupService;
    private final ProxyPoolProperties properties;
    private final JdbcResultRowMapper rowMapper;
    private final SqlExceptionTranslator sqlExceptionTranslator;
    private final Clock clock;
    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    public QueryExecutionResult execute(QueryExecutionRequest request) {
        var descriptor = datasourceLookupService.findById(request.datasourceId())
                .orElseThrow(() -> new DatasourceUnavailableException(
                        msg("error.datasource_unavailable_not_found")));
        var execProps = properties.execution();
        int effectiveMaxRows = clampMaxRows(request.maxRowsOverride(),
                descriptor.maxRowsPerQuery(), execProps.maxRows());
        Duration effectiveTimeout = request.statementTimeoutOverride() != null
                ? request.statementTimeoutOverride()
                : execProps.statementTimeout();

        var dataSource = poolManager.resolve(request.datasourceId());
        Instant start = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(request.queryType() == QueryType.SELECT);
            try (PreparedStatement statement = connection.prepareStatement(request.sql())) {
                statement.setQueryTimeout(toTimeoutSeconds(effectiveTimeout));
                statement.setFetchSize(Math.min(effectiveMaxRows + 1, execProps.defaultFetchSize()));
                if (request.queryType() == QueryType.SELECT) {
                    return runSelect(statement, effectiveMaxRows, descriptor.dbType(), start);
                }
                return runUpdate(statement, start);
            }
        } catch (SQLException ex) {
            log.debug("SQL execution failed for datasource {}: {}",
                    request.datasourceId(), ex.getMessage());
            throw sqlExceptionTranslator.translate(ex, effectiveTimeout, LocaleContextHolder.getLocale());
        }
    }

    private QueryExecutionResult runSelect(PreparedStatement statement, int effectiveMaxRows,
                                           DbType dbType, Instant start) throws SQLException {
        statement.setMaxRows(effectiveMaxRows + 1);
        try (var resultSet = statement.executeQuery()) {
            return rowMapper.materialize(resultSet, effectiveMaxRows, dbType,
                    durationSince(start));
        }
    }

    private UpdateExecutionResult runUpdate(PreparedStatement statement, Instant start)
            throws SQLException {
        long affected = statement.executeLargeUpdate();
        return new UpdateExecutionResult(affected, durationSince(start));
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }

    private static int clampMaxRows(Integer override, int datasourceCap, int globalCap) {
        int candidate = override != null ? override : datasourceCap;
        return Math.min(candidate, globalCap);
    }

    private static int toTimeoutSeconds(Duration timeout) {
        long seconds = timeout.toSeconds();
        if (seconds <= 0) {
            return 1;
        }
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }
}
